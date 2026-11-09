package com.ukkera.pos.database.independent.catalogproductversionv1

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationHandler
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalProductAttributeV1
import com.ukkera.pos.catalog.store.LocalProductAttributeValueV1
import com.ukkera.pos.catalog.store.LocalProductStatusV1
import com.ukkera.pos.catalog.store.LocalPublishedProductV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.DetachedCatalogActivationRoom
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.DetachedCatalogEd25519Signature
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.FrozenCatalogActivationMembershipV1
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.RecordingDetachedCatalogVerifier
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.RecordingDetachedCatalogView
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.catalogChangeCount
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CPR-FROZEN-1 CPR-002.A explicit-product missing-provenance read/prepare slice.
 * Uses the real file-backed Room/DAO/transaction, actual handler and JCA signature
 * verification. The detached branch metadata and signed input are data specimens;
 * neither is native root approval, completed history, employee admission or a writer.
 * No evaluate, command submission, commit, rollback, protected recovery or RPC runs.
 * Whole L2/L3 product workflows remain UNBOUND / NOT_RUN.
 */
class CatalogProductVersionFrozenUnavailableRoomTest {
    @Test fun cpr002_completeRevisionZeroWithoutNativeObservationIsAdmissionHeld() = runTest {
        val content = newProductInput()
        val signature = DetachedCatalogEd25519Signature(content.company)
        val fixture = DetachedCatalogActivationRoom.create(content.company)
        try {
            assertUnavailable(fixture, signature, signature.sign(content))
        } finally {
            fixture.close()
        }
    }

    @Test fun cpr002_matchingProductCopiesAndFileReopenCannotEstablishHistory() = runTest {
        val content = newProductInput()
        val signature = DetachedCatalogEd25519Signature(content.company)
        val signed = signature.sign(content)
        val fixture = DetachedCatalogActivationRoom.create(content.company)
        try {
            fixture.insertCatalogCopies(signed)
            val retained = fixture.snapshot()
            assertUnavailable(fixture, signature, signed)
            assertEquals(retained, fixture.snapshot())
            fixture.reopen()
            assertEquals(retained, fixture.snapshot())
            assertUnavailable(fixture, signature, signed)
            assertEquals(retained, fixture.snapshot())
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertUnavailable(fixture: DetachedCatalogActivationRoom,
        signature: DetachedCatalogEd25519Signature, signed: LocalSignedSkuV1) {
        val product = assertNotNull(signed.definition.productDefinition)
        assertEquals(DomainId("50000000-0000-4000-8000-000000000001"), product.id)
        assertEquals(Revision(0), product.revision)
        assertEquals("Plain stocked item", product.names.english)
        assertEquals("", product.names.arabic)
        assertEquals(listOf(DomainId("a1000000-0000-4000-8000-000000000004")), product.categoryIds)
        assertEquals(emptyList(), product.variantAxes)
        assertEquals(listOf(LocalProductAttributeV1(Code.exact("note"),
            LocalProductAttributeValueV1.Text(Code.exact("synthetic")))), product.attributes)
        assertEquals(LocalProductStatusV1.ACTIVE, product.status)
        assertEquals(emptyList(), signed.definition.variantValues)
        val verifier = RecordingDetachedCatalogVerifier(signature.verifier)
        val handler = LocalConfigurationHandler(verifier)
        val request = LocalConfigurationRequestV1.InstallCompanySku(signed)
        val normalized = handler.normalize(LocalConfigurationCodec.encode(request))
        assertEquals(request, LocalConfigurationCodec.decode(normalized))
        val actor = DomainId("90000000-0000-4000-8000-000000000001")
        val context = CommandContext(fixture.scope, actor, OperationId("80000000-0000-4000-8000-000000000001"),
            handler.key, semanticDigest(fixture.scope, actor, handler.key, normalized))
        val before = fixture.snapshot()
        fixture.withView { connection, actual ->
            assertNull(actual.originalNativeBusinessPublications())
            val copy = actual.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION,
                signed.definition.publicationId))
            if (fixture.hasCatalogCopies) {
                assertEquals(signed, FrozenCatalogActivationMembershipV1.signedCopy(assertNotNull(copy).payload))
                assertEquals(signed.definition.skuId, actual.claim(FrozenCatalogActivationMembershipV1.codeClaim(signed)))
            } else {
                assertNull(copy)
                assertNull(actual.claim(FrozenCatalogActivationMembershipV1.codeClaim(signed)))
            }
            val changes = connection.catalogChangeCount()
            val observed = RecordingDetachedCatalogView(actual)
            val prepared = handler.prepare(normalized, observed, context)
            assertTrue(observed.nativeObservationReads > 0)
            assertEquals(observed.nativeObservationReads, observed.unavailableObservationReads)
            assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, prepared.admissionRestriction)
            assertTrue(verifier.decisions.isNotEmpty())
            assertTrue(verifier.decisions.all { it })
            assertEquals(changes, connection.catalogChangeCount())
        }
        assertEquals(before, fixture.snapshot())
    }

    /** Exact NEW_0 content; supporting SKU-envelope IDs are not native facts. */
    private fun newProductInput(): LocalPublishedSkuV1 {
        val productId = DomainId("50000000-0000-4000-8000-000000000001")
        val product = LocalPublishedProductV1(productId, Revision(0), LocalNamesV1("Plain stocked item", ""),
            listOf(DomainId("a1000000-0000-4000-8000-000000000004")), emptyList(), listOf(
                LocalProductAttributeV1(Code.exact("note"), LocalProductAttributeValueV1.Text(Code.exact("synthetic")))),
            LocalProductStatusV1.ACTIVE)
        return LocalPublishedSkuV1(CompanyScope(DomainId("10000000-0000-4000-8000-000000000001"),
            DomainId("20000000-0000-4000-8000-000000000001")),
            DomainId("b2000000-0000-4000-8000-000000000001"), DomainId("60000000-0000-4000-8000-000000000001"),
            productId, Revision(101), Revision(41), LocalNamesV1("Plain stocked item", ""), "NEW-01", emptyList(),
            DomainId("b1000000-0000-4000-8000-000000000001"), 0, LocalTrackingV1.QUANTITY,
            LocalFulfillmentV1.RETAIL, false, true, false, true, emptyList(), emptyList(),
            productDefinition = product, variantValues = emptyList())
    }
}
