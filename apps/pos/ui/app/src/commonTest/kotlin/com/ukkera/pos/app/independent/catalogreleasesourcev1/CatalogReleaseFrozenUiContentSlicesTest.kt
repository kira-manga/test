package com.ukkera.pos.app.independent.catalogreleasesourcev1

import com.ukkera.pos.app.LocalCatalogReleaseMemberInput
import com.ukkera.pos.app.LocalCatalogReleasePublicationDraft
import com.ukkera.pos.app.LocalCatalogReleasePublicationPreview
import com.ukkera.pos.app.ui.CatalogReleaseEditor
import com.ukkera.pos.app.ui.LocalCatalogReleaseEntryDrafts
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual UI draft/editor/preview data paths only; every mapped whole CSR case remains NOT_RUN. */
class CatalogReleaseFrozenUiContentSlicesTest {
    private val inputs = FrozenCatalogReleaseUiContentInputs

    @Test
    fun csr001ReleaseSkuProductAndContentOnlyCursorAreNotInterchangeable() {
        val original = inputs.editor(0).toDraft()
        assertEquals(Revision(0), original.revision)
        assertEquals(Revision(3), original.members[0].skuRevision)
        assertEquals(Revision(2), original.members[0].productRevision)
        assertNotEquals(original.publicationId, original.members[0].publicationId)
        assertFailsWith<IllegalArgumentException> {
            inputs.editor(0).copy(publicationId = inputs.blue3.publicationId).toDraft()
        }
        for (substituted in listOf(3L, 72L)) {
            assertFailsWith<IllegalArgumentException> {
                inputs.editor(0).copy(observedRevision = Revision(substituted)).toDraft()
            }
        }
        assertEquals(inputs.draft(0), original)
        // No branch cursor, existing SKU bytes or activation effects are observed here.
    }

    @Test
    fun csr002ZeroOneSixtyFourAndSixtyFiveExerciseActualDraftBoundsAndOrder() {
        assertFailsWith<IllegalArgumentException> { inputs.draft(0, emptyList()) }
        assertEquals(listOf(inputs.blue3), inputs.draft(0, listOf(inputs.blue3)).members)
        val sixtyFive = inputs.bulkMembers()
        val sixtyFour = LocalCatalogReleasePublicationDraft(inputs.token(400), inputs.bulkCatalog,
            Revision(0), null, sixtyFive.take(64))
        assertEquals(64, sixtyFour.members.size)
        assertEquals(sixtyFive.take(64), sixtyFour.snapshot().members)
        assertFailsWith<IllegalArgumentException> {
            LocalCatalogReleasePublicationDraft(inputs.token(400), inputs.bulkCatalog, Revision(0), null, sixtyFive)
        }
        for (revision in 0..2) {
            assertEquals(inputs.orderedMembers(revision), inputs.editor(revision).toDraft().snapshot().members)
        }
        // Cardinality and order only; 48 KiB probes and genuine maximum-size authoring are unbound.
    }

    @Test
    fun csr003DuplicateSkuAndPublicationIdentitiesCannotBecomeDistinctMembers() {
        assertFailsWith<IllegalArgumentException> { inputs.draft(0, listOf(inputs.blue3, inputs.blue4)) }
        val duplicatePublication = inputs.black2.copy(publicationId = inputs.blue3.publicationId)
        assertFailsWith<IllegalArgumentException> {
            inputs.draft(0, listOf(inputs.blue3, duplicatePublication, inputs.bag0))
        }
        // Missing/wrong original coordinates require other content/native bindings, not name matching.
    }

    @Test
    fun csr004SharedProductRevisionIsIndependentAndConflictingRevisionsRefuse() {
        val validPair = inputs.draft(0, listOf(inputs.blue3, inputs.black2))
        assertEquals(listOf(Revision(3), Revision(2)), validPair.members.map { it.skuRevision })
        assertEquals(listOf(Revision(2), Revision(2)), validPair.members.map { it.productRevision })
        assertEquals(listOf(inputs.shirt, inputs.shirt), validPair.members.map { it.productId })
        assertFailsWith<IllegalArgumentException> {
            inputs.draft(0, listOf(inputs.blue3, inputs.black2.copy(productRevision = Revision(1))))
        }
        // These coordinates do not authenticate the complete product body or its P0/P1/P2 provenance.
    }

    @Test
    fun csr006DraftCannotInferMissingPredecessorSkipVersionChangeCatalogOrWrapSuccessor() {
        val retained = inputs.editor(1)
        assertFailsWith<IllegalArgumentException> { retained.copy(previous = null).toDraft() }
        assertFailsWith<IllegalArgumentException> { retained.copy(observedRevision = Revision(2)).toDraft() }
        assertFailsWith<IllegalArgumentException> { retained.copy(catalogId = inputs.otherCatalog.value).toDraft() }
        assertFailsWith<IllegalArgumentException> {
            inputs.editor(2).copy(previous = inputs.releaseReference(1).copy(revision = Revision(0))).toDraft()
        }
        val maximum = inputs.releaseReference(0).copy(revision = Revision(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> {
            CatalogReleaseEditor(inputs.token(101), inputs.catalog.value, Revision(0), maximum,
                inputs.orderedMembers(1)).toDraft()
        }
        // A typed maximum-value probe is not a fabricated enormous completed sequence.
    }

    @Test
    fun csr007FrozenEditorAndPreviewRetainExactOriginalAcrossNewerChoices() {
        val original = inputs.draft(1).snapshot()
        val retained = inputs.editor(1, frozen = true)
        val refreshedChoices = listOf(inputs.bag1, inputs.black2, inputs.blue4)
        val refreshedEditor = retained.copy(publicationId = inputs.token(102), observedRevision = Revision(2),
            previous = inputs.releaseReference(1), members = refreshedChoices)
        val replay = refreshedEditor.toDraft()
        assertEquals(original, replay)
        assertNotSame(original, replay)
        assertEquals(inputs.releaseReference(0), replay.previous)
        assertEquals(inputs.orderedMembers(1), replay.members)
        assertEquals(inputs.blue3, replay.members.last())
        assertNotEquals(refreshedChoices, replay.members)
        val preview = LocalCatalogReleasePublicationPreview(inputs.company, replay)
        assertEquals(inputs.company, preview.companyId)
        assertEquals(original, preview.definition)
        // The preview DTO is real; rendered native review/head approval remain unbound.
    }

    @Test
    fun csr008RetiringOnlyTheUiAttachmentPreservesItsExactRetainedDraft() {
        val drafts = LocalCatalogReleaseEntryDrafts()
        drafts.editor.value = inputs.editor(1, frozen = true)
        val original = requireNotNull(drafts.editor.value).toDraft()
        val oldLifetime = drafts.lifetime.identity
        drafts.retireAttachment()
        assertFalse(drafts.lifetime.isCurrent(oldLifetime))
        assertTrue(drafts.hasDraft)
        assertEquals(original, requireNotNull(drafts.editor.value).toDraft())
        assertNull(drafts.retainedActionId.value)
        // There is deliberately no invented native action/approval ID or signing outcome.
    }

    @Test
    fun csr020And022OwnedDraftAndPreviewCannotBeRelabeledThroughMutableInputsOrReads() {
        val supplied = inputs.orderedMembers(0).toMutableList()
        val original = inputs.draft(0, supplied)
        val captured = original.snapshot()
        val preview = LocalCatalogReleasePublicationPreview(inputs.company, original.snapshot())
        supplied.reverse()
        supplied[0] = inputs.bag1
        mutateDetachedRead(original.members)
        mutateDetachedRead(preview.definition.members)
        assertEquals(captured, original)
        assertEquals(captured, preview.definition)
        assertEquals(inputs.orderedMembers(0), original.members)
        assertEquals(inputs.orderedMembers(0), preview.definition.members)
        assertNotSame(original, preview.definition)
        // This preserves UI content only, not durable/native originals, actor, effects or restore evidence.
    }

    private fun mutateDetachedRead(read: List<LocalCatalogReleaseMemberInput>) {
        val mutable = read as? MutableList<LocalCatalogReleaseMemberInput> ?: return
        try { mutable.reverse(); mutable[0] = inputs.blue4 }
        catch (_: UnsupportedOperationException) { /* An immutable detached read is also safe. */ }
    }
}
