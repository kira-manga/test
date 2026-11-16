package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.*
import kotlin.test.*

/** Self-authored, NOT independent. Existing frozen publication goldens remain the byte oracle. */
class ContainerDepositPublicationTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
    private val unit = id(8)
    private val deposit = LocalContainerDepositV1(Money(Currency.EGP, 100), id(10), id(11))
    private fun legacy() = LocalPublishedSkuV1(CompanyScope(id(1), id(2)), id(3), id(4), id(5),
        Revision(1), Revision(1), LocalNamesV1("Synthetic", "اختبار"), "TEST", emptyList(), unit, 0,
        LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
        listOf(LocalPriceV1(id(12), Revision(1), null, 0, Quantity(unit, 1, 0),
            effectiveFrom = UtcInstant.parseCanonical("2026-11-14T00:00:00Z"),
            unitPrice = UnitPrice.parse(Currency.EGP, "1"), inclusion = TaxInclusion.EXCLUSIVE,
            tax = TaxSnapshot(id(13), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))))

    @Test fun absentExtensionDoesNotAddAFieldToTheExistingSignedBody() {
        val value = legacy()
        val canonical = LocalConfigurationCodec.canonicalPublication(value).decodeToString()
        assertFalse("containerDeposit" in canonical)
        val request = LocalConfigurationRequestV1.InstallCompanySku(LocalSignedSkuV1(value, id(9), "00".repeat(64)))
        val bytes = LocalConfigurationCodec.encode(request)
        assertEquals(request, LocalConfigurationCodec.decode(bytes))
        assertEquals(bytes, LocalConfigurationCodec.encode(LocalConfigurationCodec.decode(bytes)))
    }

    @Test fun termsAreInsideTheSignedBodyAndRoundTripWithoutChangingThePublicationIdentity() {
        val before = legacy()
        val value = before.copy(containerDeposit = deposit)
        val canonical = LocalConfigurationCodec.canonicalPublication(value).decodeToString()
        assertTrue("\"containerDeposit\":" in canonical)
        assertNotEquals(LocalConfigurationCodec.canonicalPublication(before).decodeToString(), canonical)
        val request = LocalConfigurationRequestV1.InstallCompanySku(LocalSignedSkuV1(value, id(9), "00".repeat(64)))
        assertEquals(request, LocalConfigurationCodec.decode(LocalConfigurationCodec.encode(request)))
        assertEquals(before.publicationId, value.publicationId)
        val changed = StoredBytes(LocalConfigurationCodec.encode(request).copyBytes().decodeToString()
            .replace("\"minor\":\"100\"", "\"minor\":\"0\"").encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { LocalConfigurationCodec.decode(changed) }
    }

    @Test fun onlyPositiveWholeQuantityRetailDepositsAreRepresentable() {
        val value = legacy().copy(containerDeposit = deposit)
        assertFailsWith<IllegalArgumentException> { deposit.copy(amountPerContainer = Money(Currency.EGP, 0)) }
        assertFailsWith<IllegalArgumentException> { value.copy(tracking = LocalTrackingV1.LOT) }
        assertFailsWith<IllegalArgumentException> { value.copy(canSell = false) }
        assertFailsWith<IllegalArgumentException> { value.copy(quantityScale = 1,
            prices = value.prices.map { it.copy(minimumQuantity = Quantity(unit, 10, 1)) }) }
        assertFailsWith<IllegalArgumentException> { value.copy(fulfillment = LocalFulfillmentV1.SERVICE) }
    }
}
