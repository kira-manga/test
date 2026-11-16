package com.ukkera.pos.database

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Self-authored, NOT independent. DATA comparisons only: no native owner, read session,
 * registered intake, invocation, force, current employee or physical capability is supplied.
 * The genuine TCP fixture separately exercises issuance by the actual original owner. */
class NativeOriginalSourceRootSupportingTest {
    private val original = DomainId.random()
    private val catalogue = DomainId.random()
    private val requirement = StoredBytes("requirement-data".encodeToByteArray())
    private val certificate = StoredBytes("certificate-data".encodeToByteArray())
    private val master = AggregateChange(AggregateKey(PhysicalRecordingSourceProfileV1.KIND, DomainId.random()), null,
        PhysicalRecordingSourceProfileV1.SCHEMA, StoredBytes("master-catalogue:$catalogue".encodeToByteArray()))
    private val client = AggregateChange(AggregateKey(PhysicalRecordingSourceProfileV1.KIND, DomainId.random()), null,
        PhysicalRecordingSourceProfileV1.SCHEMA, StoredBytes("client-source-data".encodeToByteArray()))
    private val sources get() = listOf(master, client)
    private fun retained(sources: List<AggregateChange> = this.sources,
        requirement: StoredBytes = this.requirement, certificate: StoredBytes = this.certificate) = NativeOriginalRetained(
        NativeOriginalDomain.RETAIL_PAYMENT_V3.command, NativeOriginalDomain.RETAIL_PAYMENT_V3.domainProfile,
        requirement, certificate, StoredBytes("original-data".encodeToByteArray()), sources)
    private fun snapshot(sources: List<AggregateChange> = this.sources) =
        NativeOriginalSourceSnapshot(original, requirement, certificate, sources)

    @Test fun exactRootAndPairRemainConsistentAcrossTheDifferentCatalogueNamespace() {
        assertNotEquals(original, catalogue)
        assertTrue(snapshot().matches(original, retained()))
        assertTrue(snapshot().matches(original, retained(sources.reversed())))
    }

    @Test fun anotherPosRootIncludingTheCatalogueIdentifierDoesNotMatch() {
        assertFalse(snapshot().matches(DomainId.random(), retained()))
        assertFalse(snapshot().matches(catalogue, retained()))
    }

    @Test fun changedCanonicalRequirementOrCertificateCannotMatch() {
        assertFalse(snapshot().matches(original, retained(requirement = StoredBytes("other-requirement".encodeToByteArray()))))
        assertFalse(snapshot().matches(original, retained(certificate = StoredBytes("other-certificate".encodeToByteArray()))))
    }

    @Test fun incompleteDuplicatedOrAlteredSourceFactsCannotMatch() {
        val different = AggregateChange(master.key, null, master.schema, StoredBytes("different-master-source".encodeToByteArray()))
        assertFalse(snapshot().matches(original, retained(listOf(different, client))))
        val malformedSnapshots = listOf(
            emptyList(), listOf(master), listOf(master, master), listOf(master, client, different),
            listOf(AggregateChange(master.key, Revision(1), master.schema, master.payload), client),
            listOf(AggregateChange(master.key, null, "other-source/v1", master.payload), client),
        )
        for (rows in malformedSnapshots) assertFalse(snapshot(rows).matches(original, retained()))
    }
}
