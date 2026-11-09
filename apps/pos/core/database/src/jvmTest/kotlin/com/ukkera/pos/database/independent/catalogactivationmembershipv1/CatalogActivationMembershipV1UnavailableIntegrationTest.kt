package com.ukkera.pos.database.independent.catalogactivationmembershipv1

import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationHandler
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.catalog.store.LocalConfigurationRequestV1
import com.ukkera.pos.catalog.store.LocalSignedSkuV1
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.semanticDigest
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CPM-P01/P05/P06 and CUA-06 unavailable-proof slices using the production
 * codec, signature verifier, Room database, DAO point reads and handler prepare.
 * The writer connection is real; the TransactionView has no employee owner or
 * native original frame. A cryptographically valid synthetic signature grants
 * neither native custody nor employee authorization.
 *
 * Only admissionRestriction is inspected. No evaluate, command submission,
 * commit, protected recovery or RPC occurs. Unchanged rows therefore establish
 * read/prepare conservation, not rollback or a durable activation/refusal.
 */
@RunWith(Parameterized::class)
class CatalogActivationMembershipV1UnavailableIntegrationTest(private val fixtureId: String) {
    @Test
    fun verifiedContentWithoutNativeObservationIsAdmissionHeld() = runTest {
        val content = FrozenCatalogActivationMembershipV1.content(fixtureId)
        val signature = DetachedCatalogEd25519Signature(content.company)
        val signed = signature.sign(content)
        val fixture = DetachedCatalogActivationRoom.create(content.company)
        try {
            assertUnavailableProposal(fixture, signature, signed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun matchingPersistedCopiesAndFileReopenCannotSupplyNativeObservation() = runTest {
        val content = FrozenCatalogActivationMembershipV1.content(fixtureId)
        val signature = DetachedCatalogEd25519Signature(content.company)
        val signed = signature.sign(content)
        val fixture = DetachedCatalogActivationRoom.create(content.company)
        try {
            // These are deliberately detached data specimens, not native originals
            // or the result of an authorized catalog activation.
            fixture.insertCatalogCopies(signed)
            val retained = fixture.snapshot()
            assertUnavailableProposal(fixture, signature, signed)
            assertEquals(retained, fixture.snapshot(), fixtureId)

            fixture.reopen()
            assertEquals(retained, fixture.snapshot(), fixtureId)
            assertUnavailableProposal(fixture, signature, signed)
            assertEquals(retained, fixture.snapshot(), fixtureId)
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertUnavailableProposal(
        fixture: DetachedCatalogActivationRoom,
        signature: DetachedCatalogEd25519Signature,
        signed: LocalSignedSkuV1,
    ) {
        val expectedContent = FrozenCatalogActivationMembershipV1.canonical(fixtureId)
        assertEquals(expectedContent,
            LocalConfigurationCodec.canonicalPublication(signed.definition).decodeToString(), fixtureId)
        assertEquals(fixtureId == "explicit-base", signed.definition.unitDefinitions != null, fixtureId)

        val verifier = RecordingDetachedCatalogVerifier(signature.verifier)
        val handler = LocalConfigurationHandler(verifier)
        val request = LocalConfigurationRequestV1.InstallCompanySku(signed)
        val normalized = handler.normalize(LocalConfigurationCodec.encode(request))
        assertEquals(request, LocalConfigurationCodec.decode(normalized), fixtureId)
        val context = CommandContext(fixture.scope, FrozenCatalogActivationMembershipV1.actorId,
            FrozenCatalogActivationMembershipV1.operationId, handler.key,
            semanticDigest(fixture.scope, FrozenCatalogActivationMembershipV1.actorId, handler.key, normalized))
        val before = fixture.snapshot()

        fixture.withView { connection, actual ->
            assertNull(actual.originalNativeBusinessPublications(), fixtureId)
            val copies = actual.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION,
                signed.definition.publicationId))
            if (fixture.hasCatalogCopies) {
                assertNotNull(copies, fixtureId)
                assertEquals(signed, FrozenCatalogActivationMembershipV1.signedCopy(copies.payload), fixtureId)
                assertEquals(signed.definition.skuId,
                    actual.claim(FrozenCatalogActivationMembershipV1.codeClaim(signed)), fixtureId)
            } else {
                assertNull(copies, fixtureId)
                assertNull(actual.claim(FrozenCatalogActivationMembershipV1.codeClaim(signed)), fixtureId)
            }

            val changesBefore = connection.catalogChangeCount()
            val observed = RecordingDetachedCatalogView(actual)
            val prepared = handler.prepare(normalized, observed, context)

            // Verify the actual handler consumed unavailable original membership,
            // including the legacy candidate with its absent unit extension.
            assertTrue(observed.nativeObservationReads > 0, fixtureId)
            assertEquals(observed.nativeObservationReads, observed.unavailableObservationReads, fixtureId)
            assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE,
                prepared.admissionRestriction, fixtureId)
            // This control rules out an unsupported-signature-only explanation.
            // Real signature validity is a data property, not native approval.
            assertTrue(verifier.decisions.isNotEmpty(), fixtureId)
            assertTrue(verifier.decisions.all { it }, fixtureId)
            assertEquals(changesBefore, connection.catalogChangeCount(), fixtureId)
        }

        assertEquals(before, fixture.snapshot(), fixtureId)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun frozenInputs(): List<Array<Any>> = listOf("legacy", "explicit-base").map { arrayOf<Any>(it) }
    }
}
