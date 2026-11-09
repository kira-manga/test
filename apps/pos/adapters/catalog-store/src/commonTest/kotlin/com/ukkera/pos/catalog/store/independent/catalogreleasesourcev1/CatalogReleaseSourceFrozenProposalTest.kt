package com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1

import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.localCatalogReleaseReference
import com.ukkera.pos.catalog.store.makeNativeCatalogReleaseProposal
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Inert proposal shape only: no owner, review, signature, completed output or native snapshot. */
class CatalogReleaseSourceFrozenProposalTest {
    @Test fun csr001_exactInertReleaseProposalUsesItsOwnKindIdentityAndCanonicalBytes() {
        val fixture = CatalogReleaseSourceFrozenFixture
        val release = fixture.releases[0]
        val skuBefore = fixture.definitions.mapValues { LocalConfigurationCodec.canonicalPublication(it.value) }
        val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(release)
        val proposal = makeNativeCatalogReleaseProposal(release)

        assertEquals(CompanyBusinessPublicationKind.CATALOG_RELEASE_V1, proposal.kind)
        assertNotEquals(CompanyBusinessPublicationKind.CATALOG_SKU_V1.domain, proposal.kind.domain)
        assertEquals(release.company, proposal.company)
        assertEquals(release.publicationId, proposal.publicationId)
        assertEquals(release.catalogId, proposal.entityId)
        assertEquals(localCatalogReleaseReference(release).contentDigest, proposal.contentDigest)
        assertContentEquals(canonical, proposal.copyCanonicalContent())
        val suppliedCopy = proposal.copyCanonicalContent()
        suppliedCopy.fill(0)
        assertContentEquals(canonical, proposal.copyCanonicalContent())
        assertEquals(release, LocalCatalogReleaseCodecV1.decodeCanonicalPublication(proposal.copyCanonicalContent()))
        for ((alias, before) in skuBefore) {
            assertContentEquals(before, LocalConfigurationCodec.canonicalPublication(fixture.sku(alias)))
        }
        // No branch activation, other-company state, current grants or completion is inferred.
    }
}
