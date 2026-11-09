package com.ukkera.pos.catalog.store.independent.retailorderrpcworkflowv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import kotlin.test.*

/**
 * retail-order-rpc-workflow-v1 RO-S-01 SKU-selector SUPPORT only. The production
 * codec and classifier bind F-CLOSED/F-RETAIL using the unavailable verifier.
 * No signed SKU, permitted projection, page traversal, order authority, installed
 * channel/source, RPC, database preparation or stock/money effect is established.
 */
class RetailOrderRpcSkuSelectorSupportingTest {
    private val handler get() = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
    private val retail get() = LocalMasterRpcWorkflowProfile.RETAIL_ORDERS
    private val skuA get() = DomainId("10000000-0000-4000-8000-000000000031")

    @Test
    fun roS01SkuExactIdLeadingZeroBarcodeAndBoundedPageRemainCanonicalSelectors() {
        val queries = listOf(LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = skuA),
            LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, barcode = "0000001234567"),
            LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, limit = 1),
            LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, after = skuA, limit = 1))
        val classifier = handler
        queries.forEach { query ->
            val body = LocalConfigurationCodec.query(query)
            assertEquals(query, LocalConfigurationCodec.decodeQuery(body))
            assertEquals(body, classifier.normalize(body))
            assertTrue(classifier.supportsRpcWorkflow(retail, body))
        }
        assertEquals("0000001234567", LocalConfigurationCodec.decodeQuery(
            LocalConfigurationCodec.query(queries[1])).barcode)
        // Request selection/copy does not prove the frozen permitted two-page SKU results.
    }

    @Test
    fun roS01EveryOtherDeclaredConfigurationSelectorIsOutsideRetailOrders() {
        val classifier = handler
        listOf(LocalConfigurationProfile.WAREHOUSE, LocalConfigurationProfile.BASIS,
            LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SUPPLIER,
            LocalConfigurationProfile.SALES_ROUTE).forEach { kind ->
            val body = LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, limit = 1))
            assertEquals(body, classifier.normalize(body))
            assertFalse(classifier.supportsRpcWorkflow(retail, body), kind)
        }
        val sku = LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = skuA))
        LocalMasterRpcWorkflowProfile.entries.filter { it != retail }.forEach {
            assertFalse(classifier.supportsRpcWorkflow(it, sku), it.name)
        }
    }

    @Test
    fun roS01UnknownConfigurationAndExistingPageOverLimitCannotBeAdmitted() {
        val classifier = handler
        val unknown = StoredBytes("{\"kind\":\"unknown.retail.selector\",\"id\":null,\"after\":null,\"limit\":1,\"barcode\":null}"
            .encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { classifier.normalize(unknown) }
        assertFailsWith<IllegalArgumentException> { LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, limit = 51) }
        val valid = LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, id = skuA))
        assertFailsWith<IllegalArgumentException> { classifier.normalize(StoredBytes(valid.copyBytes().copyOf(valid.size - 1))) }
        assertEquals(valid, classifier.normalize(valid))
        // Only constructor/typed normalization refusal is supplied, not a protected dispatch/effect observation.
    }
}
