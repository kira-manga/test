package com.ukkera.pos.app.gateway.independent.immutablesalesdocuments

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.GrantEffect
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Bounded SUPPORT portions of frozen IRD v1, not whole-case execution.
 * Source fields absent from PaidSaleV1 are not fabricated. The complete original,
 * current Master, native persistence/RPC, zero authorization, reprint and Z ledgers
 * remain unbound. Exact rendering/layout/approved translations are not qualified.
 */
class ImmutableSalesDocumentMapperSupportingTest {
    private val f = ImmutableSalesDocumentSupportingInputs

    @Test
    fun supportIrd01MixedTraversalPreservesAllProvidedFinancialFields() = runTest {
        val source = f.mixed()
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            val before = fixture.payload.copyBytes()
            val pages = traverse(fixture)
            assertCensus(source.value, pages)
            assertEquals(34200L, source.value.payable.minor)
            assertEquals(listOf(14250L, 19950L), source.value.lines.map { it.payable.minor })
            assertEquals(30000L, source.value.components.filter { it.kind == "ITEM_NET" }.sumOf { it.amount.minor })
            assertEquals(4200L, source.value.components.filter { it.kind == "ITEM_TAX" }.sumOf { it.amount.minor })
            assertEquals(listOf(18200L, 16000L), source.value.tenders.map { it.amount.minor })
            assertEquals(6, source.value.components.count { it.kind.startsWith("CHARGE_") && it.amount.minor == 0L })
            assertContentEquals(before, fixture.payload.copyBytes())
            fixture.assertOnlyOriginalSaleReads(3)
        }
        // Cash received 20000/change 1800, actors, original number and fulfillment
        // are not exposed by this preview and are not replaced by net payment data.
    }

    @Test
    fun supportIrd03PostedPromotionIsNotDiscountedAgain() = runTest {
        val source = f.promo()
        assertFalse(source.tenderMembershipBound)
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            val pages = traverse(fixture)
            assertCensus(source.value, pages)
            val header = saleHeader(pages.first())
            assertEquals("185.00 EGP", field(header, "Original before discount").english)
            assertEquals("38.50 EGP", field(header, "Original advertised discount").english)
            assertEquals("146.50 EGP", field(header, "Original payable").english)
            assertEquals(listOf("73.25 EGP", "73.25 EGP"), portions(pages).map { field(it, "Original payable").english })
            assertEquals(14650L, source.value.components.sumOf { it.amount.minor })
            assertFalse(pages.toString().contains("108.00 EGP"))
            fixture.assertOnlyOriginalSaleReads(2)
        }
        // Saved promotion terms, per-unit discount 1925 and later policy changes
        // are absent/unexecuted; this checks original posted paid values only.
    }

    @Test
    fun supportIrd24All137Portions119875AndSecondaryComponentsAppearOnce() = runTest {
        val source = f.large()
        assertFalse(source.tenderMembershipBound)
        assertEquals(137, source.value.lines.size)
        assertEquals(119875L, source.value.payable.minor)
        assertTrue(source.value.lines.all { it.payable.minor == 875L && it.quantity.scaled == 1L && it.quantity.scale == 0 })
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            // Actual existing wire bound, not an approved document/product maximum.
            assertTrue(fixture.payload.size <= 65536, "Keep the full frozen fixture; refuse rather than shrink it")
            val pages = traverse(fixture)
            assertCensus(source.value, pages)
            assertEquals(14, pages.size)
            assertEquals(listOf(20, 20, 20, 20, 20, 20, 17), pages.take(7).map { portions(listOf(it)).size })
            assertEquals(listOf(20, 20, 20, 20, 20, 20, 17), pages.drop(7).map { componentValues(listOf(it)).size })
            assertEquals((1..6).map { "sale-portions:${it * 20}" } + listOf("sale-components:0") +
                (1..6).map { "sale-components:${it * 20}" } + listOf(null), pages.map { it.nextCursor })
            assertEquals(119875L, source.value.lines.sumOf { it.payable.minor })
            pages.forEach { assertEquals("1198.75 EGP", field(saleHeader(it), "Original payable").english) }
            fixture.assertOnlyOriginalSaleReads(14)
        }
        // Long sold descriptions/export interruption and complete original tax,
        // charge, tender, print/copy or legal content are not carried by this DTO.
    }

    @Test
    fun supportIrd08ExplicitZeroHasNoFabricatedTenderWhileUnavailableIsNotZero() = runTest {
        val source = f.zero()
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            val pages = traverse(fixture)
            assertCensus(source.value, pages)
            assertEquals("342.00 EGP", field(saleHeader(pages.first()), "Original before discount").english)
            assertEquals("342.00 EGP", field(saleHeader(pages.first()), "Original advertised discount").english)
            assertEquals("0.00 EGP", field(saleHeader(pages.first()), "Original payable").english)
            assertTrue(payments(pages).isEmpty())
            assertNull(pages.last().nextCursor)
            fixture.assertOnlyOriginalSaleReads(2)
        }
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            fixture.replace(LocalCollectionReadResultV1.NotAvailable)
            val page = fixture.read()
            assertTrue(page.records.isEmpty() && page.forms.isEmpty())
            assertNull(page.nextCursor)
            assertTrue(assertNotNull(page.notice).english.contains("This is not absence of payment"))
            assertTrue(assertNotNull(page.notice).arabic.contains("هذا لا يثبت عدم الدفع"))
            assertFalse(page.toString().contains("0.00 EGP"))
            fixture.assertOnlyOriginalSaleReads(1)
        }
        // Authentic authorization/discount/stock ancestry for zero remains NOT_RUN.
    }

    @Test
    fun supportIrd06And25SelectedSaleRoutesReuseTheRealHeaderAndOriginalTenderIdentity() = runTest {
        val source = f.mixed()
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            val selected = fixture.read(workflow = LocalWorkflow.CHECKOUT)
            val searched = fixture.readQuery(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = "sale:${source.value.saleId.value}"))
            assertEquals(selected.records, searched.records)
            assertEquals(selected.nextCursor, searched.nextCursor)
            val tenderPage = fixture.read("sale-tenders:0")
            assertCensus(source.value, listOf(selected, fixture.read("sale-components:0"), tenderPage))
            assertEquals(source.value.saleId, saleHeader(tenderPage).reference.id)
            val card = payments(listOf(tenderPage)).single { it.reference.id == source.value.tenders[1].originalPaymentId }
            assertEquals(source.value.tenders[1].tender.tenderId!!.value, field(card, "Original tender ID").english)
            assertEquals("71", field(card, "Original tender-policy revision").english)
            val cash = payments(listOf(tenderPage)).single { it.reference.id == source.value.tenders[0].originalPaymentId }
            assertEquals(LocalText("Not recorded", "غير مسجل"), field(cash, "Original tender-policy revision"))
            fixture.assertOnlyOriginalSaleReads(4)
        }
        // Identity is the source sale, not a synthesized receipt/number/copy ID.
    }

    @Test
    fun supportIrd13SeparateLinkHintsNeverReadOrAuthorizeRelatedRecords() = runTest {
        val source = f.mixed()
        for (hint in listOf(null, GrantEffect.DENY, GrantEffect.ALLOW)) {
            ImmutableSalesDocumentMapperFixture(source, customerReceiptHint = hint).use { fixture ->
                val pages = traverse(fixture)
                pages.forEach { page ->
                    val links = page.records.filter { it.reference.kind in linkKinds }
                    assertEquals(fixture.expectedLinkKinds, links.map { it.reference.kind }.toSet())
                    assertTrue(links.all { it.reference.id == source.value.saleId && it.values.isEmpty() })
                }
                fixture.assertOnlyOriginalSaleReads(3)
            }
        }
        ImmutableSalesDocumentMapperFixture(source, customerReceiptHint = null, correctionInstalled = false).use { fixture ->
            assertTrue(fixture.read().records.none { it.reference.kind in linkKinds })
            fixture.assertOnlyOriginalSaleReads(1)
        }
        // A correction navigation ID is not a correction VIEW/print/export grant;
        // actual current authorization on the separately opened routes is unbound.
    }

    @Test
    fun supportIrd25SourceMismatchAndDuplicateMembershipCannotReachPresentation() = runTest {
        val source = f.mixed()
        val value = source.value
        val other = f.promo().value
        val invalid = listOf(other,
            value.copy(lines = listOf(value.lines.first(), value.lines.first())),
            value.copy(components = listOf(value.components.first(), value.components.first())),
            value.copy(tenders = listOf(value.tenders.first(), value.tenders.first())))
        for (reply in invalid) ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            fixture.replace(LocalCollectionReadResultV1.PaidSale(reply))
            val failure = assertFailsWith<GatewayFailure> { fixture.read("sale-components:0") }
            assertEquals(LocalServiceProblem.UNAVAILABLE, failure.problem)
            assertFalse(failure.toString().contains(other.saleId.value))
            fixture.assertOnlyOriginalSaleReads(1)
        }
        ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            val old = fixture.read()
            fixture.replace(LocalCollectionReadResultV1.PaidSale(other))
            assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure> {
                fixture.read(assertNotNull(old.nextCursor)) }.problem)
            assertEquals(value.saleId, saleHeader(old).reference.id)
            fixture.assertOnlyOriginalSaleReads(2)
        }
        // Cross-tenant native joins, source-version conflicts and persistence are unbound.
    }

    @Test
    fun supportIrd24MalformedOrOutOfRangeSectionCursorsRefuseWithoutPartialOutput() = runTest {
        val cursors = listOf("other:0", "sale-portions:-1", "sale-portions:3", "sale-portions:1.5",
            "sale-portions:2147483648", "sale-components:11", "sale-components:0:1", "sale-tenders:3", "sale-tenders:bad")
        cursors.forEach { cursor -> ImmutableSalesDocumentMapperFixture(f.mixed()).use { fixture ->
            assertEquals(LocalServiceProblem.INVALID_INPUT, assertFailsWith<GatewayFailure> { fixture.read(cursor) }.problem)
            fixture.assertOnlyOriginalSaleReads(1)
        } }
        ImmutableSalesDocumentMapperFixture(f.mixed()).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read("X".repeat(513)) }
            fixture.assertOnlyOriginalSaleReads(0)
        }
        // Exact public cursor/error/size contracts remain U-09; these are bounded mapper guards.
    }

    @Test
    fun supportIrd22UnsupportedMissingAndOversizedProvidedFieldsRefuseInsteadOfDefaulting() = runTest {
        val source = f.mixed()
        val variants = listOf(source.value.copy(components = listOf(source.value.components.first().copy(kind = "UNKNOWN"))),
            source.value.copy(tenders = listOf(source.value.tenders.first().copy(tender = LocalTenderViewV1("UNKNOWN")))))
        variants.forEach { value -> ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            fixture.replace(LocalCollectionReadResultV1.PaidSale(value))
            val cursor = if (value.components.singleOrNull()?.kind == "UNKNOWN") "sale-components:0" else "sale-tenders:0"
            assertEquals(LocalServiceProblem.UNAVAILABLE, assertFailsWith<GatewayFailure> { fixture.read(cursor) }.problem)
            fixture.assertOnlyOriginalSaleReads(1)
        } }
        for (field in listOf("saleId", "beforeDiscount", "discount", "payable", "lines", "components", "tenders")) {
            ImmutableSalesDocumentMapperFixture(source).use { fixture ->
                fixture.removeRequiredField(field)
                assertFailsWith<IllegalArgumentException> { fixture.read() }
                fixture.assertOnlyOriginalSaleReads(1)
            }
        }
        val oversized = listOf(source.value.components.first().copy(key = "X".repeat(2049)),
            source.value.components.first().copy(taxCategory = "X".repeat(2049)))
        oversized.forEach { component -> ImmutableSalesDocumentMapperFixture(source).use { fixture ->
            fixture.replace(LocalCollectionReadResultV1.PaidSale(source.value.copy(components = listOf(component))))
            assertFailsWith<IllegalArgumentException> { fixture.read("sale-components:0") }
            fixture.assertOnlyOriginalSaleReads(1)
        } }
        // This refuses missing PROVIDED wire fields. Required full-document fields
        // absent from PaidSaleV1 remain gaps, not repaired/defaulted by these tests.
    }

    @Test
    fun supportIrd13And15EndpointDenialAndLeaseLossFencePreviewRelease() = runTest {
        for (reason in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRONG_SCOPE)) {
            ImmutableSalesDocumentMapperFixture(f.mixed()).use { fixture ->
                fixture.denial = reason
                assertEquals(LocalServiceProblem.DENIED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
                fixture.assertOnlyOriginalSaleReads(1)
            }
        }
        ImmutableSalesDocumentMapperFixture(f.zero()).use { fixture ->
            fixture.denial = ReadFailure.AUTHORIZATION_DENIED
            assertEquals(LocalServiceProblem.DENIED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlyOriginalSaleReads(1)
        }
        ImmutableSalesDocumentMapperFixture(f.mixed()).use { fixture ->
            fixture.leaseCurrent = false
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlyOriginalSaleReads(0)
        }
        ImmutableSalesDocumentMapperFixture(f.mixed()).use { fixture ->
            fixture.afterRead = { fixture.leaseCurrent = false }
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertFailsWith<GatewayFailure> { fixture.read() }.problem)
            fixture.assertOnlyOriginalSaleReads(1)
        }
        // Controlled refusal and lease fencing are not actual activated policy,
        // revocation serialization, account switching or current-Master proof.
    }

    private suspend fun traverse(fixture: ImmutableSalesDocumentMapperFixture): List<LocalWorkflowPage> {
        val pages = mutableListOf<LocalWorkflowPage>()
        val cursors = mutableSetOf<String?>()
        var cursor: String? = null
        do {
            assertTrue(cursors.add(cursor), "A sequential document traversal must not cycle")
            val page = fixture.read(cursor)
            assertPreviewBounds(page, fixture.source.value, fixture.expectedLinkKinds)
            pages += page
            cursor = page.nextCursor
        } while (cursor != null)
        return pages
    }

    private fun assertPreviewBounds(page: LocalWorkflowPage, source: LocalPaidSaleViewV1, expectedLinks: Set<LocalRecordKind>) {
        assertTrue(page.forms.isEmpty())
        val header = saleHeader(page)
        assertEquals(LocalRecordReference(LocalRecordKind.SALE, source.saleId), header.reference)
        assertTrue(header.title.english.contains("financial preview") && header.title.arabic.contains("معاينة مالية"))
        val status = assertNotNull(header.status)
        assertTrue(status.english.contains("Original recorded basis") && status.english.contains("read only"))
        val notice = assertNotNull(page.notice)
        listOf("Read-only financial preview", "already discounted", "not reconciled tender classifications",
            "not a complete printed, legal or fiscal receipt", "bank verification or proof of goods delivery",
            "No print/reprint or tax/return action is supplied").forEach { assertTrue(notice.english.contains(it), it) }
        listOf("معاينة مالية للقراءة فقط", "محسوم منها الخصم بالفعل", "ليست إيصالًا مطبوعًا أو قانونيًا أو ضريبيًا كاملًا",
            "لا تتوفر إجراءات طباعة أو إعادة طباعة أو ضرائب أو مرتجع").forEach { assertTrue(notice.arabic.contains(it), it) }
        assertTrue(page.records.size <= 23 && header.values.size <= 27)
        assertTrue(page.records.all { record -> record.values.size <= 32 && record.values.all {
            it.label.english.length <= 2048 && it.label.arabic.length <= 2048 && it.value.english.length <= 2048 && it.value.arabic.length <= 2048 } })
        assertEquals(expectedLinks, page.records.filter { it.reference.kind in linkKinds }.map { it.reference.kind }.toSet())
        assertTrue(page.records.filter { it.reference.kind in linkKinds }.all { it.reference.id == source.saleId && it.values.isEmpty() })
        assertTrue(portions(listOf(page)).size + payments(listOf(page)).size + componentValues(listOf(page)).size <= 20)
        val common = header.values.filterNot { it.label.english.contains("(") }
        assertEquals(7, common.size)
        assertLiteral(header, "Original sale ID", source.saleId.value)
        assertLiteral(header, "Original before discount", f.moneyText(source.beforeDiscount.minor))
        assertLiteral(header, "Original advertised discount", f.moneyText(source.discount.minor))
        assertLiteral(header, "Original payable", f.moneyText(source.payable.minor))
        assertLiteral(header, "Original sold portions", source.lines.size.toString())
        assertLiteral(header, "Original posted components", source.components.size.toString())
        assertLiteral(header, "Original tenders", source.tenders.size.toString())
    }

    private fun assertCensus(source: LocalPaidSaleViewV1, pages: List<LocalWorkflowPage>) {
        val portions = portions(pages)
        assertEquals(source.lines.map { it.portionId }, portions.map { it.reference.id })
        assertEquals(portions.size, portions.map { it.reference.id }.distinct().size)
        source.lines.zip(portions).forEach { (line, record) ->
            assertEquals(LocalRecordReference(LocalRecordKind.ORDER_LINE, line.portionId, parentId = source.saleId), record.reference)
            assertFalse(record.selectable)
            assertEquals(3, record.values.size)
            assertEquals(LocalText(line.portionId.value, line.portionId.value), field(record, "Portion ID"))
            assertEquals(LocalText(f.quantityText(line.quantity), f.quantityText(line.quantity)), field(record, "Original quantity"))
            assertEquals(LocalText(f.moneyText(line.payable.minor), f.moneyText(line.payable.minor)), field(record, "Original payable"))
        }
        val components = componentValues(pages)
        assertEquals(source.components.size, components.size)
        source.components.zip(components).forEach { (component, record) ->
            assertTrue(record.label.english.endsWith("(${component.kind}) · ${component.key}"))
            assertTrue(record.label.arabic.endsWith("(${component.kind}) · ${component.key}"))
            val amount = f.moneyText(component.amount.minor)
            assertEquals("Already-discounted posted amount: $amount · Tax category: ${component.taxCategory} · Portion ID: ${component.portionId?.value ?: "Not present"} · Charge ID: ${component.chargeId?.value ?: "Not present"}", record.value.english)
            assertEquals("المبلغ المثبت بعد الخصم بالفعل: $amount · الفئة الضريبية: ${component.taxCategory} · معرف الجزء: ${component.portionId?.value ?: "غير موجود"} · معرف الرسم: ${component.chargeId?.value ?: "غير موجود"}", record.value.arabic)
        }
        val payments = payments(pages)
        assertEquals(source.tenders.map { it.originalPaymentId }, payments.map { it.reference.id })
        assertEquals(payments.size, payments.map { it.reference.id }.distinct().size)
        source.tenders.zip(payments).forEach { (payment, record) ->
            assertEquals(LocalRecordReference(LocalRecordKind.PAYMENT, payment.originalPaymentId, parentId = source.saleId), record.reference)
            assertFalse(record.selectable)
            assertEquals(7, record.values.size)
            assertEquals(LocalText(payment.originalPaymentId.value, payment.originalPaymentId.value), field(record, "Original payment ID"))
            val kind = when (payment.tender.kind) {
                "CASH" -> LocalText("Cash · CASH", "نقدي · CASH")
                "CARD" -> LocalText("External card · CARD", "بطاقة خارجية · CARD")
                else -> error("This frozen monetary binding supplies only original cash and external card")
            }
            assertEquals(kind, field(record, "Original tender type"))
            assertEquals(payment.tender.tenderId?.let { LocalText(it.value, it.value) } ?: LocalText("Not recorded", "غير مسجل"), field(record, "Original tender ID"))
            assertEquals(payment.tender.policyRevision?.let { LocalText(it.value.toString(), it.value.toString()) } ?: LocalText("Not recorded", "غير مسجل"), field(record, "Original tender-policy revision"))
            assertEquals(LocalText(f.moneyText(payment.amount.minor), f.moneyText(payment.amount.minor)), field(record, "Original payment amount"))
            assertEquals(yesNo(payment.tender.requiresTerminal), field(record, "Original terminal requirement"))
            assertEquals(yesNo(payment.tender.requiresReference), field(record, "Original reference requirement"))
        }
    }

    private fun yesNo(value: Boolean) = if (value) LocalText("Yes", "نعم") else LocalText("No", "لا")
    private fun saleHeader(page: LocalWorkflowPage) = page.records.single { it.reference.kind == LocalRecordKind.SALE }
    private fun field(record: LocalRecord, label: String) = record.values.single { it.label.english == label }.value
    private fun assertLiteral(record: LocalRecord, label: String, value: String) = assertEquals(LocalText(value, value), field(record, label))
    private fun portions(pages: List<LocalWorkflowPage>) = pages.flatMap { it.records }.filter { it.reference.kind == LocalRecordKind.ORDER_LINE }
    private fun payments(pages: List<LocalWorkflowPage>) = pages.flatMap { it.records }.filter { it.reference.kind == LocalRecordKind.PAYMENT }
    private fun componentValues(pages: List<LocalWorkflowPage>) = pages.flatMap { saleHeader(it).values }.filter { it.label.english.contains("(") }
    private val linkKinds = setOf(LocalRecordKind.CUSTOMER_RECEIPT, LocalRecordKind.PAYMENT_CORRECTION_SOURCE)
}
