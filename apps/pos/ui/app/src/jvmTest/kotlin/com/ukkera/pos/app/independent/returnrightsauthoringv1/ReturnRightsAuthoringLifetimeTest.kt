package com.ukkera.pos.app.independent.returnrightsauthoringv1

import com.ukkera.pos.app.ui.LocalReturnRightsEntryDrafts
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real entry holders and negative callback ownership; controlled observers prove neither native review nor secret cleanup. */
class ReturnRightsAuthoringLifetimeTest {
    private val inputs = FrozenReturnRightsAuthoringUiInputs

    @Test
    fun raa15AttachmentRetirementInvalidatesBeforeObserverCleanupWithoutRetaggingUnsignedOriginalCoordinates() {
        val drafts = populated()
        val captured = drafts.lifetime.identity
        var retired = 0
        drafts.lifetime.attach(captured) {
            assertFalse(drafts.lifetime.isCurrent(captured))
            retired++
        }
        drafts.retireAttachment()
        assertEquals(1, retired)
        assertFalse(drafts.lifetime.isCurrent(captured))
        inputs.assertLiteralDraft(requireNotNull(drafts.editor.value).toDraft())
        assertEquals(DomainId(inputs.operationId.value), drafts.retainedActionId.value)
        assertTrue(drafts.approvalHeld.value)
        drafts.retireAttachment()
        assertEquals(1, retired)
    }

    @Test
    fun raa15DeniedClearPurgesAllEntryHeldInputAndCoordinatesEvenIfItsObserverFails() {
        val drafts = populated()
        var observerFails = true
        drafts.lifetime.attach(drafts.lifetime.identity) {
            if (observerFails) throw IllegalStateException("Controlled observer failure")
        }
        assertFailsWith<IllegalStateException> { drafts.clear(denied = true) }
        assertFalse(drafts.hasDraft)
        assertNull(drafts.editor.value)
        assertNull(drafts.retainedActionId.value)
        assertFalse(drafts.approvalHeld.value)
        assertNull(drafts.notice.value)
        assertTrue(drafts.lifetime.accessDenied)
        // Keep the synthetic fault active through the failure and every purge assertion.
        // Resolve the same callback only for the later retirement, not as fresh-access proof.
        observerFails = false
        drafts.retireAttachment()
        assertTrue(drafts.lifetime.accessDenied)
        drafts.lifetime.accessObserved()
        assertFalse(drafts.lifetime.accessDenied)
        assertFalse(drafts.hasDraft)
    }

    @Test
    fun raa15RetiredObserversAndOldDisposersCannotOwnTheReplacementEntry() {
        val drafts = populated()
        val first = drafts.lifetime.identity
        val events = mutableListOf<String>()
        val releaseOld = drafts.lifetime.attach(first) { events += "old" }
        drafts.lifetime.attach(first) { events += "replacement" }
        assertEquals(listOf("old"), events)
        releaseOld()
        drafts.retireAttachment()
        assertEquals(listOf("old", "replacement"), events)
        val current = drafts.lifetime.identity
        drafts.lifetime.attach(current) { events += "current" }
        drafts.lifetime.attach(first) { events += "stale rejected" }
        releaseOld()
        assertEquals(listOf("old", "replacement", "stale rejected"), events)
        drafts.clear()
        assertEquals(listOf("old", "replacement", "stale rejected", "current"), events)
        assertFalse(drafts.hasDraft)
    }

    private fun populated() = LocalReturnRightsEntryDrafts().apply {
        editor.value = inputs.editor()
        retainedActionId.value = DomainId(inputs.operationId.value)
        approvalHeld.value = true
        notice.value = inputs.draft().continuingRights
    }
}
