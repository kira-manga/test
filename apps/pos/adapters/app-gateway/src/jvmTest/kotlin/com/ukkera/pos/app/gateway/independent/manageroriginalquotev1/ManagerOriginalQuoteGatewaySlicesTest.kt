@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.manageroriginalquotev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.managerExactValues
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen controlled public gateway/display slices; no installed UI or durable-state acceptance. */
class ManagerOriginalQuoteGatewaySlicesTest {
    @Test
    fun a002BothInstalledPreferExactlyOneQuoteReadWithNoLegacyOrOwnerFallback() = runTest {
        val control = OriginalQuoteGatewayControl()
        val page = assertIs<LocalReadResult.Available>(control.gateway.read(OriginalQuoteFrozen.workflow())).page
        assertNotNull(page.managerQuoteDetails?.originalQuote)
        assertEquals(OriginalQuoteFrozen.creator.value,
            page.records.single { it.reference.kind == LocalRecordKind.ORDER }.values.single { it.label.english == "Original creator" }.value.english)
        control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
    }

    @Test
    fun a002OnlyInstalledLegacyV1IsIncompleteAndMissingManagerKeysNeverUseOwnerOrV2() = runTest {
        val legacy = OriginalQuoteGatewayControl(reads = setOf(OriginalQuoteFrozen.legacyKey) + OriginalQuoteFrozen.ownerKeys,
            response = { legacyData(LocalOrderReadResultV2(listOf(OriginalQuoteFrozen.entry().target), null)) })
        val page = assertIs<LocalReadResult.Available>(legacy.gateway.read(OriginalQuoteFrozen.workflow())).page
        assertNull(page.managerQuoteDetails?.originalQuote)
        assertTrue(page.records.flatMap { it.values }.none { it.label.english == "Original creator" })
        assertTrue(page.forms.isEmpty() && page.records.all { !it.selectable })
        legacy.assertCalls(listOf(OriginalQuoteFrozen.legacyKey))
        for (reads in listOf(emptySet(), OriginalQuoteFrozen.ownerKeys + ReadKey("sales.order.manager-review", 2))) {
            val absent = OriginalQuoteGatewayControl(reads = reads)
            val unavailable = assertIs<LocalReadResult.Unavailable>(absent.gateway.read(OriginalQuoteFrozen.workflow()))
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, unavailable.problem)
            absent.assertCalls(emptyList())
        }
    }

    @Test
    fun a001InvalidManagerRoutesRefuseBeforeAnyTransportDispatch() = runTest {
        val target = OriginalQuoteFrozen.exclusive.id("target_order_id")
        val other = OriginalQuoteFrozen.corruptions.single { it.text("id") == "C-TARGET-ID" }.id("replacement")
        val variants = listOf(
            LocalWorkflowQuery(LocalWorkflow.ORDERS, "manager:not-a-uuid"),
            OriginalQuoteFrozen.workflow().copy(reference = LocalRecordReference(LocalRecordKind.ORDER, other)),
            OriginalQuoteFrozen.workflow().copy(cursor = "nonempty-cursor"),
            LocalWorkflowQuery(LocalWorkflow.CHECKOUT, "manager:" + target.value),
            LocalWorkflowQuery(LocalWorkflow.CATALOG, "manager:" + target.value))
        for (query in variants) {
            val control = OriginalQuoteGatewayControl()
            assertIs<LocalReadResult.Unavailable>(control.gateway.read(query), "Invalid route must not publish private or empty success")
            control.assertCalls(emptyList())
        }
    }

    @Test
    fun a003DeniedUnavailableUncertainAndIncompatibleRepliesRemainFailureWithoutFallback() = runTest {
        for (reason in ReadFailure.entries) {
            val control = OriginalQuoteGatewayControl(response = { ReadReply.Unavailable(reason) })
            assertIs<LocalReadResult.Unavailable>(control.gateway.read(OriginalQuoteFrozen.workflow()),
                "An installed quote reader's failure must not become private or successful-empty content")
            control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
        }
    }

    @Test
    fun a003CancellationPropagatesAndNeverBecomesEmptyOrRetriesAnotherReader() = runTest {
        val control = OriginalQuoteGatewayControl(response = { throw CancellationException("Frozen CANCELLED variant") })
        assertFailsWith<CancellationException> { control.gateway.read(OriginalQuoteFrozen.workflow()) }
        control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
    }

    @Test
    fun a006InvalidSingletonTargetCursorPartialAndCorruptRepliesRefuseWhileAuthorizedAbsenceIsClear() = runTest {
        val original = controlElement(OriginalQuoteFrozen.entry(), LocalManagerOriginalQuote.serializer()).jsonObject
        val mismatched = corruptQuoteEntry(original, OriginalQuoteFrozen.corruptions.single { it.text("id") == "C-TARGET-ID" })
        val partial = original.changed("quoteSnapshot", JsonNull)
        val conflicting = corruptQuoteEntry(original, OriginalQuoteFrozen.corruptions.single { it.text("id") == "C-SNAPSHOT-ID" })
        val invalid = listOf(
            buildJsonObject { put("orders", JsonArray(listOf(mismatched))) },
            buildJsonObject { put("orders", JsonArray(listOf(original, original))) },
            buildJsonObject { put("orders", JsonArray(listOf(original))); put("nextAfter", "nonempty-cursor") },
            buildJsonObject { put("orders", JsonArray(listOf(partial))) },
            buildJsonObject { put("orders", JsonArray(listOf(conflicting))) })
        for (body in invalid) {
            val control = OriginalQuoteGatewayControl(response = { quoteData(body) })
            assertIs<LocalReadResult.Unavailable>(control.gateway.read(OriginalQuoteFrozen.workflow()))
            control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
        }
        val absentTarget = buildJsonObject { put("target_order_id", OriginalQuoteFrozen.declaration("F-ABSENT").text("order_id")) }
        val absent = OriginalQuoteGatewayControl(fixture = absentTarget, response = { quoteData(LocalManagerOriginalQuoteResult(emptyList())) })
        val page = assertIs<LocalReadResult.Available>(absent.gateway.read(OriginalQuoteFrozen.workflow(absentTarget))).page
        assertTrue(page.records.isEmpty() && page.forms.isEmpty())
        assertNull(page.managerQuoteDetails)
        assertNull(page.nextCursor)
        assertTrue(assertNotNull(page.notice).english.contains("No retail target"))
        absent.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
    }

    @Test
    fun a004ExclusiveDisplayPreservesCreatorStoredUnitTaxAndDistinctNetVersusPayableDiscountInBothLanguages() = runTest {
        val control = OriginalQuoteGatewayControl()
        val page = assertIs<LocalReadResult.Available>(control.gateway.read(OriginalQuoteFrozen.workflow())).page
        assertFrozenExclusiveDisplay(page)
        control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
    }

    @Test
    fun a007RetiredCapturedCredentialLeaseRefusesLatePrivateReply() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val control = OriginalQuoteGatewayControl(response = {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); fullQuoteData() }
        })
        val pending = async { control.gateway.read(OriginalQuoteFrozen.workflow()) }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            control.leaseCurrent = false
            control.attachedLease = OriginalQuoteFrozen.nextSession
            release.complete(Unit)
            val failed = assertIs<LocalReadResult.Unavailable>(pending.await())
            assertEquals(LocalServiceProblem.SESSION_ENDED, failed.problem)
            control.assertCalls(listOf(OriginalQuoteFrozen.quoteKey))
        } finally { release.complete(Unit) }
    }

    @Test
    fun a010DirectLongRatioDisplayKeepsBothFrozen1024DigitFieldsAndShortCanonicalRatio() {
        val fixture = OriginalQuoteFrozen.declaration("F-LONG-RATIO")
        val label = LocalText("Exact ratio", "نسبة دقيقة")
        val ratio = ExactRational.parseCanonical(fixture.text("numerator") + "/" + fixture.text("denominator"))
        val fields = managerExactValues(label, ratio)
        assertEquals(2, fields.size)
        val numerator = fields.single { it.label.english.contains("numerator") && it.label.arabic.contains("البسط") }
        val denominator = fields.single { it.label.english.contains("denominator") && it.label.arabic.contains("المقام") }
        for (rendered in listOf(numerator.value.english, numerator.value.arabic)) {
            assertEquals(fixture.text("numerator"), rendered)
            assertEquals(fixture.long("numerator_digits").toInt(), rendered.length)
            assertTrue(rendered.length <= 2048)
        }
        for (rendered in listOf(denominator.value.english, denominator.value.arabic)) {
            assertEquals(fixture.text("denominator"), rendered)
            assertEquals(fixture.long("denominator_digits").toInt(), rendered.length)
            assertTrue(rendered.length <= 2048)
        }
        val short = managerExactValues(label, ExactRational.parseCanonical(fixture.text("short_ratio_control"))).single().value
        assertEquals(fixture.text("short_expected_single_field"), short.english)
        assertEquals(fixture.text("short_expected_single_field"), short.arabic)
    }
}

internal fun assertFrozenExclusiveDisplay(page: LocalWorkflowPage) {
    val f = OriginalQuoteFrozen.exclusive
    val portion = f.array("portions").single().jsonObject
    val allocation = portion.array("discount_allocations").single().jsonObject
    val summary = page.records.single { it.reference.kind == LocalRecordKind.ORDER }
    fun literal(values: List<LocalRecordValue>, label: String, expected: String) {
        val value = values.single { it.label.english == label }.value
        assertEquals(expected, value.english)
        assertEquals(expected, value.arabic)
    }
    fun money(values: List<LocalRecordValue>, label: String, expected: Long) {
        val value = values.single { it.label.english == label }.value
        for (rendered in listOf(value.english, value.arabic)) {
            assertEquals(expected, BigDecimal(rendered.substringBefore(' ')).movePointRight(2).longValueExact())
            assertEquals(f.text("currency"), rendered.substringAfter(' ').trim())
        }
    }
    fun exact(values: List<LocalRecordValue>, label: String, expected: ExactRational) {
        val value = values.single { it.label.english == label }.value
        for (rendered in listOf(value.english, value.arabic)) {
            assertEquals(expected.canonicalString(), rendered.substringBefore(' '))
            assertEquals(f.text("currency"), rendered.substringAfter(' ').trim())
        }
    }
    literal(summary.values, "Current owner", f.text("current_owner"))
    literal(summary.values, "Original creator", f.text("actual_original_creator"))
    money(summary.values, "Before discount", f.obj("summary").long("before_discount_payable_minor"))
    money(summary.values, "Advertised discount", f.obj("summary").long("advertised_payable_reduction_minor"))
    money(summary.values, "Exact payable", f.obj("summary").long("exact_final_payable_minor"))
    val details = assertNotNull(page.managerQuoteDetails)
    val original = assertNotNull(details.originalQuote)
    literal(original.values, "Original quote ID", f.text("snapshot_id"))
    literal(original.values, "Original quote revision", f.text("stored_snapshot_revision"))
    val line = original.sections.single { it.key == "line:" + portion.text("portion_id") }
    for (rendered in listOf(line.values.single { it.label.english == "Original unit price" }.value.english,
        line.values.single { it.label.english == "Original unit price" }.value.arabic)) {
        assertEquals(0, BigDecimal(portion.text("unit_price_exact_EGP")).compareTo(BigDecimal(rendered.substringBefore(' '))))
        assertEquals(f.text("currency"), rendered.substringAfter(' ').trim())
    }
    literal(line.values, "Price revision", portion.text("price_context_revision"))
    literal(line.values, "Tax policy ID", portion.text("tax_policy_id"))
    literal(line.values, "Tax policy revision", portion.text("tax_policy_revision"))
    literal(line.values, "Tax category", portion.text("tax_category"))
    literal(line.values, "Tax percentage", OriginalQuoteFrozen.percentage(portion.obj("tax_rate")).percent.canonicalString())
    val inclusion = line.values.single { it.label.english == "Tax inclusion" }.value
    assertEquals("Tax exclusive", inclusion.english)
    assertEquals("غير شامل الضريبة", inclusion.arabic)
    exact(line.values, "Exact original item net", OriginalQuoteFrozen.major(portion.obj("original_item_net_exact_minor")))
    exact(line.values, "Exact discounted item net", OriginalQuoteFrozen.major(portion.obj("discounted_item_net_exact_minor")))
    val net = details.components.single { it.values.single { value -> value.label.english == "Recorded component" }.value.english == "Item net" }
    val tax = details.components.single { it.values.single { value -> value.label.english == "Recorded component" }.value.english == "Item tax" }
    money(net.values, "Recorded posted amount", portion.obj("posted_components_minor").long("ITEM_NET"))
    money(net.values, "Before-discount posting", portion.obj("original_posted_components_minor").long("ITEM_NET"))
    money(tax.values, "Recorded posted amount", portion.obj("posted_components_minor").long("ITEM_TAX"))
    money(tax.values, "Before-discount posting", portion.obj("original_posted_components_minor").long("ITEM_TAX"))
    val allocationSections = original.sections.filter { it.key.startsWith("allocation:") }
    val netAllocation = allocationSections.single { it.values.single { value -> value.label.english == "Component kind" }.value.english == "Item net" }
    val taxAllocation = allocationSections.single { it.values.single { value -> value.label.english == "Component kind" }.value.english == "Item tax" }
    exact(netAllocation.values, "Exact reduction", OriginalQuoteFrozen.major(allocation.obj("item_net_reduction_exact_minor")))
    money(netAllocation.values, "Posted reduction", allocation.long("posted_item_net_reduction_minor"))
    exact(taxAllocation.values, "Exact reduction", OriginalQuoteFrozen.major(allocation.obj("item_tax_reduction_exact_minor")))
    money(taxAllocation.values, "Posted reduction", allocation.long("posted_item_tax_reduction_minor"))
    assertNotEquals(allocation.long("posted_item_net_reduction_minor"), f.obj("summary").long("advertised_payable_reduction_minor"))
    assertTrue(page.forms.isEmpty() && page.records.all { !it.selectable })
    assertNull(page.nextCursor)
    assertNull(page.priceControls)
    val notice = assertNotNull(page.notice)
    assertTrue(notice.english.contains("not transfer eligibility") && notice.english.contains("prior collectors/cash custody and unresolved-payment facts are not supplied"))
    assertTrue(notice.arabic.contains("ليست أهلية") && notice.arabic.contains("المحصلين السابقين") &&
        notice.arabic.contains("عهدة النقد") && notice.arabic.contains("حقائق الدفع غير المحسوم"))
    // Translation-node, live catalog and complete native history variants remain unbound.
}
