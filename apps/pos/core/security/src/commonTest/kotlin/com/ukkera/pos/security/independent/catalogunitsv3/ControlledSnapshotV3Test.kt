package com.ukkera.pos.security.independent.catalogunitsv3

import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** CUA-04/05 controlled storage checks only; no genuine native refresh or authority proof. */
class ControlledSnapshotV3Test {
    private fun assertOriginals(f: ControlledSnapshotV3Fixture, snapshot: NativeCompanyBusinessPublicationSnapshot) {
        assertEquals(f.company, snapshot.company)
        assertEquals(f.root, snapshot.rootFingerprint)
        assertEquals(7L, snapshot.generation)
        val originals = snapshot.completedPublications
        assertEquals(2, originals.size)
        assertEquals(listOf(f.action1, f.action2), originals.map { it.originalActionId })
        assertEquals(listOf(f.key1, f.key2), originals.map { it.signingKeyId })
        assertEquals(listOf(f.publication1, f.publication2), originals.map { it.proposal.publicationId })
        assertEquals(listOf("01".repeat(64), "02".repeat(64)), originals.map { it.signatureHex })
        assertEquals(listOf(f.company, f.company), originals.map { it.proposal.company })
        assertContentEquals(ControlledSnapshotV3Fixture.FIRST_CONTENT.encodeToByteArray(), originals[0].proposal.copyCanonicalContent())
        assertContentEquals(ControlledSnapshotV3Fixture.SECOND_CONTENT.encodeToByteArray(), originals[1].proposal.copyCanonicalContent())
    }

    @Test
    fun cua04_initial_equivalent_getters_are_null_and_never_call_publication_operations() {
        val f = ControlledSnapshotV3Fixture()
        repeat(3) {
            assertNull(f.publications.currentCompletedSnapshot())
            assertNull(f.trust.currentCompletedSnapshot())
        }
        assertEquals(0, f.controller.calls)
    }

    @Test
    fun cua04_controlled_completed_getters_preserve_identity_generation_and_originals() {
        val f = ControlledSnapshotV3Fixture()
        f.retainControlledSnapshot()
        repeat(3) {
            assertOriginals(f, assertNotNull(f.publications.currentCompletedSnapshot()))
            assertOriginals(f, assertNotNull(f.trust.currentCompletedSnapshot()))
        }
        assertEquals(0, f.controller.calls)
    }

    @Test
    fun cua05_input_lists_and_buffers_are_copied_before_caller_mutation() {
        val f = ControlledSnapshotV3Fixture()
        f.retainControlledSnapshot()
        f.inputOutputs.clear()
        f.input1.fill(0)
        f.input2.fill(0)
        assertOriginals(f, assertNotNull(f.publications.currentCompletedSnapshot()))
        assertEquals(0, f.controller.calls)
    }

    @Test
    fun cua05_returned_list_and_nested_content_mutation_cannot_rewrite_originals() {
        val f = ControlledSnapshotV3Fixture()
        f.retainControlledSnapshot()
        val held = assertNotNull(f.publications.currentCompletedSnapshot())
        val exposed: List<CompanyBusinessPublicationOutput> = held.completedPublications
        if (exposed is MutableList<*>) {
            try { exposed.clear() } catch (_: UnsupportedOperationException) { /* A refused mutation is permitted. */ }
        }
        val contentCopy = held.completedPublications[0].proposal.copyCanonicalContent()
        val marker = "\"maximumScale\":0"
        val scaleIndex = ControlledSnapshotV3Fixture.FIRST_CONTENT.indexOf(marker) + marker.lastIndex
        contentCopy[scaleIndex] = '1'.code.toByte()
        held.completedPublications[1].proposal.copyCanonicalContent().fill(0)
        assertOriginals(f, held)
        assertOriginals(f, assertNotNull(f.publications.currentCompletedSnapshot()))
        assertEquals(0, f.controller.calls)
    }

    @Test
    fun cua04_cua05_explicit_invalidation_makes_new_reads_null_and_keeps_held_content() {
        val f = ControlledSnapshotV3Fixture()
        f.retainControlledSnapshot()
        val held = assertNotNull(f.publications.currentCompletedSnapshot())
        f.trust.invalidateCompletedSnapshot()
        repeat(3) {
            assertNull(f.publications.currentCompletedSnapshot())
            assertNull(f.trust.currentCompletedSnapshot())
        }
        assertOriginals(f, held)
        assertEquals(0, f.controller.calls)
    }
}
