package com.ukkera.pos.app

import androidx.compose.ui.unit.LayoutDirection
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.foodKitchenNoticeText
import com.ukkera.pos.types.*
import kotlin.test.*

/**
 * Frozen EN/AR meaning slices using actual shared notice copy and controlled check inputs.
 * The freeze prescribes meanings, not literal sentences: fragments below bind concepts only.
 * No Compose rendering, focus, live-region announcement or installed-device result is claimed.
 */
class FoodBlockedUnpaidFrozenNoticeSlicesTest {
    @Test
    fun fbu01And02BlockedUnpaidNoticeExplainsPermittedSaveAddAndNoPhysicalEffectsInBothLanguages() {
        for (base in listOf(FbuUiInputs.r0, FbuUiInputs.c0, FbuUiInputs.r2, FbuUiInputs.c2)) {
            for (channel in listOf("DINE_IN", "TAKEAWAY")) {
                val order = base.copy(channel = channel)
                val body = FbuUiInputs.body(order)
                val copy = foodKitchenNoticeText(body)
                assertConcepts(copy.english, listOf("unpaid", "not paid"), listOf("kitchen"), listOf("unavailable"),
                    listOf("sav"), listOf("add"), listOf("not reserve", "no reservation"),
                    listOf("not reserve or issue", "no issue"), listOf("kitchen work"), listOf("collect money"),
                    listOf("sending"), listOf("starting collection", "start collection"), listOf("blocked", "prevented"))
                assertConcepts(copy.arabic, listOf("غير مدفوعة"), listOf("المطبخ"), listOf("غير متاح"),
                    listOf("حفظ"), listOf("إضاف"), listOf("لا يحجز"), listOf("لا يحجز أو يصرف", "لا يصرف"),
                    listOf("لا ينشئ"), listOf("لا يحصّل"), listOf("الإرسال"), listOf("بدء التحصيل"), listOf("محظور", "ممنوع"))
                assertBilingualSelection(copy)
                assertEquals(order.orderId, body.check.orderId)
                assertEquals(Revision(7), body.check.orderStorageRevision)
                assertEquals(Money(Currency.EGP, order.quoteMinor), body.originalQuotedTotal)
                assertNull(body.check.collectionId)
                assertNull(body.financial)
                assertTrue(body.portions.all { it.quantity == FbuUiInputs.one &&
                    it.preparation == LocalFoodPreparation.NOT_SENT && it.itemNote == FbuUiInputs.itemNote })
            }
        }
    }

    @Test
    fun fbu11AbsentAndExplicitUnknownNoticeNeverClaimsAvailableOrConfirmedKitchenAbsence() {
        val absentBody = FbuUiInputs.body(FbuUiInputs.r2, null)
        val unknownBody = FbuUiInputs.body(FbuUiInputs.r2, LocalFoodUiKitchenStatus.UNKNOWN)
        val absent = foodKitchenNoticeText(absentBody)
        val unknown = foodKitchenNoticeText(unknownBody)
        assertEquals(absent, unknown)
        assertConcepts(unknown.english, listOf("unknown"), listOf("not proof", "does not prove"),
            listOf("Master checks", "Master authoriz"), listOf("saved items"), listOf("original"), listOf("separate"))
        assertConcepts(unknown.arabic, listOf("غير معروفة"), listOf("ليست إثبات", "لا تثبت"),
            listOf("يتحقق الجهاز الرئيسي"), listOf("المحفوظة"), listOf("الأصلية"), listOf("منفصلة"))
        assertNotEquals(foodKitchenNoticeText(FbuUiInputs.body(FbuUiInputs.r2)), unknown)
        assertBilingualSelection(unknown)
        for (body in listOf(absentBody, unknownBody)) {
            assertFalse(body.kitchenUnavailableForUnsentWork)
            assertEquals(FbuUiInputs.r2.orderId, body.check.orderId)
            assertEquals(Revision(7), body.check.orderStorageRevision)
            assertEquals(Money(Currency.EGP, 20_000), body.originalQuotedTotal)
            assertNull(body.check.collectionId)
            assertNull(body.financial)
        }
    }

    @Test
    fun fbu01And24ZeroUnsentNoticeIsNotKitchenCapabilityOrFinancialSettlement() {
        for (order in listOf(FbuUiInputs.r0, FbuUiInputs.issued)) {
            val body = FbuUiInputs.body(order, LocalFoodUiKitchenStatus.NO_UNSENT_WORK)
            val copy = foodKitchenNoticeText(body)
            assertConcepts(copy.english, listOf("no unsent", "no items awaiting"), listOf("does not prove", "not proof"),
                listOf("kitchen"), listOf("settle"), listOf("payment"), listOf("refund"),
                listOf("issued work"), listOf("original-operation recovery", "original recovery"), listOf("separate rules"))
            assertConcepts(copy.arabic, listOf("لا توجد"), listOf("غير مرسلة", "بانتظار الإرسال"), listOf("لا يثبت"),
                listOf("المطبخ"), listOf("لا يحسم", "ولا يحسم"), listOf("الدفع"), listOf("الرد"),
                listOf("المصروف"), listOf("استعادة العملية الأصلية"), listOf("المنفصلة"))
            assertBilingualSelection(copy)
            assertEquals(0, body.kitchen?.unsentPortionCount)
            assertFalse(body.kitchenUnavailableForUnsentWork)
            assertEquals(order.orderId, body.check.orderId)
            assertEquals(Money(Currency.EGP, order.quoteMinor), body.originalQuotedTotal)
            assertTrue(body.portions.all { it.preparation == LocalFoodPreparation.QUEUED })
        }
    }

    @Test
    fun fbu06And24MixedBlockedNoticePreservesIssuedWorkAndExistingMoneyRecoveryMeanings() {
        val body = FbuUiInputs.body(FbuUiInputs.mixed)
        val copy = foodKitchenNoticeText(body)
        assertConcepts(copy.english, listOf("unavailable"), listOf("unsent work"), listOf("cannot be sent"),
            listOf("start collection"), listOf("previously issued work"), listOf("recorded payments"),
            listOf("refunds"), listOf("original-operation recovery", "original recovery"),
            listOf("current authorization"), listOf("does not undo", "does not reverse"))
        assertConcepts(copy.arabic, listOf("غير متاح"), listOf("غير المرسل"), listOf("لا يمكن إرسال"),
            listOf("بدء تحصيل"), listOf("المصروف"), listOf("المدفوعات"), listOf("الردود"),
            listOf("استعادة العملية الأصلية"), listOf("صلاحياته"), listOf("لا يتراجع", "لا يلغي"))
        assertBilingualSelection(copy)
        assertEquals(FbuUiInputs.mixed.orderId, body.check.orderId)
        assertEquals(Revision(7), body.check.orderStorageRevision)
        assertEquals(2, body.kitchen?.unsentPortionCount)
        assertTrue(body.kitchenUnavailableForUnsentWork)
        assertEquals(LocalFoodPreparation.QUEUED, body.portions.first().preparation)
        assertEquals(listOf(FbuUiInputs.id("321"), FbuUiInputs.id("322"), FbuUiInputs.id("323")),
            body.portions.map { it.portionId })
        assertEquals(Money(Currency.EGP, 30_000), body.originalQuotedTotal)
        // No payment/custody original is fabricated to qualify that separate recovery requirement.
        assertNull(body.financial)
    }
}

private fun assertConcepts(value: String, vararg concepts: List<String>) {
    concepts.forEach { alternatives ->
        assertTrue(alternatives.any { value.contains(it, ignoreCase = true) },
            "Required semantic concept is missing: ${alternatives.joinToString(" / ")}")
    }
}

private fun assertBilingualSelection(copy: LocalText) {
    assertTrue(copy.english.isNotBlank())
    assertTrue(copy.arabic.isNotBlank())
    assertNotEquals(copy.english, copy.arabic)
    assertEquals(copy.english, AppLanguage.ENGLISH.text(copy))
    assertEquals(copy.arabic, AppLanguage.ARABIC.text(copy))
    assertEquals(LayoutDirection.Ltr, AppLanguage.ENGLISH.direction)
    assertEquals(LayoutDirection.Rtl, AppLanguage.ARABIC.direction)
}
