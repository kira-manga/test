package com.ukkera.pos.app.independent.localserialregistration

import com.ukkera.pos.app.*
import com.ukkera.pos.app.ui.LocalSerialRegistrationEntryDrafts
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Frozen L1 editor/negative-lifetime slices; no composed card or native success fixture. */
class LocalSerialRegistrationFrozenEditorSlicesTest {
    private val f = LsrUiInputs

    @Test fun lsr06And07_editorKeepsExactRequestedPermanentAndSuccessorCoordinates() {
        val draft = f.editor().draft()
        assertEquals(f.registration, draft.registrationId)
        assertEquals(f.publication, draft.publicationId)
        assertEquals(Revision(4), draft.revision)
        assertEquals(f.source().previous, draft.previous)
        assertEquals(f.serial, draft.serialId)
        assertEquals("000AbC-07", draft.serialCode)
        assertEquals(f.sku, draft.skuId)
        assertEquals(f.skuPublication, draft.skuPublicationId)
        assertEquals(f.digest, draft.skuPublicationDigest)
        assertEquals(f.from, draft.validFrom)
        assertEquals(f.until, draft.validUntil)
        // A requested predecessor is still not an authenticated completed original.
    }

    @Test fun lsr01And03_sourceAbsenceCannotBeFilledFromMatchingTypedIdentifiers() {
        val input = f.editor().copy(source = null)
        assertFailsWith<IllegalArgumentException> { input.draft() }
        assertNull(input.frozen)
        assertEquals(f.publication, input.publicationId)
        assertEquals(f.serial, input.serialId)
    }

    @Test fun lsr10And11_wrongSourceRegistrationOrSkuCannotBeAdopted() {
        val input = f.editor()
        val wrong = listOf(
            input.copy(registrationId = "a1000000-0000-4000-8000-000000000002"),
            input.copy(skuId = "d1000000-0000-4000-8000-000000000002"))
        wrong.forEach { assertFailsWith<IllegalArgumentException> { it.draft() } }
        assertEquals(f.registration, input.draft().registrationId)
        assertEquals(f.sku, input.draft().skuId)
    }

    @Test fun lsr06And07_successorEditorUsesPreviousSerialIdentityRatherThanFreshInput() {
        val fresh = f.id("c1000000-0000-4000-8000-000000000002")
        val input = f.editor().copy(serialId = fresh)
        assertEquals(f.serial, input.draft().serialId)
        assertEquals(f.publication, input.draft().publicationId)
        assertEquals(f.source().previous, input.draft().previous)
        assertEquals(Revision(4), input.draft().revision)
    }

    @Test fun lsr12And13_frozenRequestSurvivesLaterVisibleEditsWithoutChangedOriginalContent() {
        val input = f.editor()
        val original = input.draft()
        val frozen = input.copy(frozen = original)
        val changed = frozen.copy(serialCode = "000AbC-08", from = "malformed", source = null,
            publicationId = f.id("b1000000-0000-4000-8000-000000000005"))
        assertSame(original, changed.draft())
        assertEquals(f.publication, changed.draft().publicationId)
        assertEquals(f.code, changed.draft().serialCode)
        assertEquals(f.from, changed.draft().validFrom)
        assertTrue(changed.dirty)
        // UI immutability only; no statement about signing/save/activation completion.
    }

    @Test fun lsr05And06_caseAndLeadingZerosRemainExactForNewInputCandidates() {
        val codes = listOf("000AbC-07", "000abc-07", "0AbC-07")
        assertEquals(codes, codes.map { f.editor(successor = false).copy(serialCode = it).draft().serialCode })
        // Representation of distinct candidate inputs is not a uniqueness or native pass.
    }

    @Test fun lsr08_optionalEndIsAbsentRatherThanAnInventedActualTime() {
        val draft = f.editor().copy(until = "").draft()
        assertEquals(f.from, draft.validFrom)
        assertNull(draft.validUntil)
        assertEquals(f.publication, draft.publicationId)
    }

    @Test fun lsr07And21_exhaustedAuthoringSourceCannotWrapToAnotherRevision() {
        val input = f.editor().copy(source = f.source().copy(nextRevision = null))
        assertFailsWith<IllegalArgumentException> { input.draft() }
        assertEquals(f.publication, input.publicationId)
        assertNull(input.frozen)
    }

    @Test fun lsr13And17_attachmentRetirementClearsTransientPresentationAndKeepsOriginalDraft() {
        val holder = LocalSerialRegistrationEntryDrafts()
        val editor = f.editor().let { it.copy(frozen = it.draft()) }
        holder.editor.value = editor
        holder.original.value = f.id(f.operation.value)
        holder.approvalHeld.value = true
        val captured = holder.lifetime.identity
        var transient = "private head-entry data"
        holder.lifetime.attach(captured) { transient = "" }
        holder.retireAttachment()
        assertFalse(holder.lifetime.isCurrent(captured))
        assertEquals("", transient)
        assertEquals(editor, holder.editor.value)
        assertEquals(f.id(f.operation.value), holder.original.value)
        assertTrue(holder.approvalHeld.value)
        assertTrue(holder.hasDraft)
        // Holder retention is not durable recovery, native reservation or a grant.
    }

    @Test fun lsr11And17_denialDestroysPrivateEditorAndInvalidatesOldCallbacks() {
        val holder = LocalSerialRegistrationEntryDrafts()
        holder.editor.value = f.editor()
        holder.original.value = f.id(f.operation.value)
        holder.approvalHeld.value = true
        val captured = holder.lifetime.identity
        var cleaned = false
        holder.lifetime.attach(captured) { cleaned = true }
        holder.clear(denied = true)
        assertTrue(cleaned)
        assertFalse(holder.lifetime.isCurrent(captured))
        assertTrue(holder.lifetime.accessDenied)
        assertFalse(holder.hasDraft)
        assertNull(holder.editor.value.frozen)
        assertNull(holder.original.value)
        assertFalse(holder.approvalHeld.value)
    }

    @Test fun lsr17And19_cleanupFailureStillHidesInputAndRetainsNegativeCleanupForRetry() {
        val holder = LocalSerialRegistrationEntryDrafts()
        holder.editor.value = f.editor()
        holder.original.value = f.id(f.operation.value)
        val captured = holder.lifetime.identity
        var attempts = 0
        holder.lifetime.attach(captured) { attempts++; if (attempts == 1) error("controlled cleanup failure") }
        assertFails { holder.clear(denied = true) }
        assertFalse(holder.lifetime.isCurrent(captured))
        assertTrue(holder.lifetime.accessDenied)
        assertFalse(holder.hasDraft)
        assertNull(holder.original.value)
        holder.clear(denied = true)
        assertEquals(2, attempts)
        assertFalse(holder.hasDraft)
    }

    @Test fun lsr01And03_unavailablePublicationCompositionDoesNotInventReviewCompletionOrActivation() = runTest {
        val gateway = UnavailableLocalSerialRegistrationPublication
        assertFalse(gateway.configured)
        assertEquals(LocalServiceProblem.NOT_CONFIGURED,
            assertIs<LocalSerialPublicationStatusResult.Unavailable>(gateway.status()).problem)
        val results = listOf(gateway.prepareAuthority(), gateway.prepare(f.editor().draft()),
            gateway.review(f.id(f.operation.value)), gateway.completed(f.id(f.operation.value)))
        results.forEach {
            val refusal = assertIs<LocalSerialPublicationResult.Unavailable>(it)
            assertEquals(LocalServiceProblem.NOT_CONFIGURED, refusal.problem)
            assertFalse(refusal.unsignedInputMayChange)
        }
        assertEquals(LocalServiceProblem.NOT_CONFIGURED,
            assertIs<LocalStageResult.NotStaged>(gateway.stageActivation(f.id(f.operation.value))).problem)
    }
}
