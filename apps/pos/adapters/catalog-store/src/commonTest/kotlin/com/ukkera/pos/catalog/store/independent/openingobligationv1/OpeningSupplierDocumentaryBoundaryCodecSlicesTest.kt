package com.ukkera.pos.catalog.store.independent.openingobligationv1

import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationCodec
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import kotlin.test.*

/**
 * Frozen OSD-BND v1 byte-bound/intake slices through the public request codec only.
 * No Master posting, durable capture, protected read, UI refusal, media intake or
 * saved-intent replacement is exercised. No whole boundary case is qualified here.
 */
class OpeningSupplierDocumentaryBoundaryCodecSlicesTest {
    private val f = OpeningSupplierDocumentaryBoundaryCodecInputs

    @Test
    fun osdBnd01And02And03AcceptedBoundaryOriginalsNormalizeWithoutByteChanges() {
        for (original in f.accepted) {
            assertFrozenAsset(original)
            val wire = StoredBytes(f.rawRequest(original))
            val normalized = LocalOpeningSupplierObligationCodec.normalize(wire)
            val request = LocalOpeningSupplierObligationCodec.decodeRequest(normalized)
            assertEquals(original.obligation, request.obligationId, original.fixtureId)
            assertEquals(f.supplier, request.supplierId, original.fixtureId)
            assertEquals(1L, request.expectedSupplierRevision.value, original.fixtureId)
            assertEquals(f.evidence, request.sourceEvidenceId, original.fixtureId)
            assertEquals(f.source, request.sourceDocument.sourceIdentity, original.fixtureId)
            assertContentEquals(original.raw,
                request.sourceDocument.text.encodeToByteArray(throwOnInvalidSequence = true), original.fixtureId)
            assertEquals(f.reference, request.sourceReference, original.fixtureId)
            assertEquals(f.date, request.openingDate.canonicalString(), original.fixtureId)
            assertEquals(Currency.EGP, request.amount.currency, original.fixtureId)
            assertEquals(125075L, request.amount.minor, original.fixtureId)
            assertEquals(f.terms, request.paymentTerms, original.fixtureId)
            assertEquals(normalized, LocalOpeningSupplierObligationCodec.request(request), original.fixtureId)
        }
        // SHA-256 checks freeze the input assets, not a required product digest algorithm.
    }

    @Test
    fun osdBnd04And05EmptyAnd4097ByteOriginalsCannotBecomeNormalizedRequests() {
        for (id in listOf("empty", "ascii-4097", "unicode-4097")) {
            val original = f.refused.single { it.fixtureId == id }
            assertFrozenAsset(original)
            assertContentEquals(original.raw, original.text().encodeToByteArray(throwOnInvalidSequence = true), id)
            if (id == "unicode-4097") {
                assertEquals(4085, original.text().length, id)
                assertTrue(original.text().length < 4096, "The frozen candidate is below the code-unit cap")
            }
            val wire = StoredBytes(f.rawRequest(original))
            assertTrue(wire.size < LocalOpeningSupplierObligationProfile.MAX_INPUT_BYTES,
                "Document refusal must not be confused with an oversized transport fixture")
            assertFails(id) { LocalOpeningSupplierObligationCodec.decodeRequest(wire) }
            assertFails(id) { LocalOpeningSupplierObligationCodec.normalize(wire) }
        }
        // No posting service was invoked; economic zero-effects and visible refusal remain unbound.
    }

    @Test
    fun osdBnd04ExactMalformedUtf8CandidatesAreNotReplacementDecodedByRequestIntake() {
        for (original in f.refused.filter { it.fixtureId.startsWith("utf8-") }) {
            assertFrozenAsset(original)
            val wire = StoredBytes(f.rawRequest(original))
            assertTrue(wire.size < LocalOpeningSupplierObligationProfile.MAX_INPUT_BYTES, original.fixtureId)
            // The original raw invalid bytes enter the actual codec; the harness never decodes
            // them to a repaired String. Only JSON syntax/control bytes are transport-escaped.
            assertFails(original.fixtureId) { LocalOpeningSupplierObligationCodec.decodeRequest(wire) }
            assertFails(original.fixtureId) { LocalOpeningSupplierObligationCodec.normalize(wire) }
        }
        // This supported byte-wire boundary is not an installed editor/media flow or safe
        // replacement of an already saved original. Those complete REFUSED_SAFE oracles remain gaps.
    }

    private fun assertFrozenAsset(original: OpeningDocumentaryBoundaryCodecOriginal) {
        assertEquals(original.expectedByteSize, original.raw.size, original.fixtureId)
        assertEquals(original.assetSha256, sha256(original.raw).value, original.fixtureId)
    }
}
