package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlin.test.*

/** Self-authored, NOT independent. Pure correlation DATA controls: no grant, native owner,
 * original or writer is minted. Ordinary READ may survive an unrelated global commit;
 * cursor-returning APIs retain the exact cut, and every current security/payload field stays.
 * Genuine two-Client execution is separately required; this is not TCP qualification. */
class LocalMasterReadProjectionBindingSupportingTest {
    private fun id(n: Int) = DomainId("00000001-0000-4000-8000-${n.toString().padStart(12, '0')}")
    private fun cursor() = LocalMasterChangeCursorV1(
        BranchScope(id(1), id(2), id(3)), AuthorityIdentity(id(4), id(5), Revision(1)),
        Revision(7), JournalSequence(12), sha256(byteArrayOf(1)), id(6), id(7), id(8),
        SessionGeneration(1), Revision(2), Revision(3), LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
        sha256(byteArrayOf(2)), sha256(byteArrayOf(3)))

    @Test fun unrelatedGlobalCommitPreservesOnlyTheOrdinaryReadProjection() {
        val before = cursor()
        for (after in listOf(before.copy(branchRevision = Revision(8)),
            before.copy(sequence = JournalSequence(13)), before.copy(journalHash = sha256(byteArrayOf(9))),
            before.copy(branchRevision = Revision(8), sequence = JournalSequence(13), journalHash = sha256(byteArrayOf(9))))) {
            assertEquals(LocalMasterChangesBindingV1.readProjectionDigest(before), LocalMasterChangesBindingV1.readProjectionDigest(after))
            assertNotEquals(LocalMasterChangesBindingV1.digest(listOf(before)), LocalMasterChangesBindingV1.digest(listOf(after)))
        }
    }

    @Test fun everyCurrentSecurityAndSelectorFieldRemainsBound() {
        val original = cursor()
        val changes = listOf(
            original.copy(scope = original.scope.copy(branchId = id(10))),
            original.copy(authority = original.authority.copy(assignmentId = id(11))),
            original.copy(authority = original.authority.copy(deviceId = id(12))),
            original.copy(authority = original.authority.copy(epoch = Revision(2))),
            original.copy(actorId = id(13)), original.copy(deviceId = id(14)), original.copy(sessionId = id(15)),
            original.copy(sessionGeneration = SessionGeneration(2)), original.copy(admissionRevision = Revision(3)),
            original.copy(policyRevision = Revision(4)), original.copy(profile = LocalMasterRpcWorkflowProfile.SUPPLIERS),
            original.copy(selectorDigest = sha256(byteArrayOf(10))))
        for (changed in changes) assertNotEquals(LocalMasterChangesBindingV1.readProjectionDigest(original),
            LocalMasterChangesBindingV1.readProjectionDigest(changed))
    }

    @Test fun changedAuthorizedPayloadCannotReleaseThePreviousRead() {
        val before = cursor()
        val changed = before.copy(projectionDigest = sha256(byteArrayOf(11)))
        assertNotEquals(LocalMasterChangesBindingV1.readProjectionDigest(before), LocalMasterChangesBindingV1.readProjectionDigest(changed))
    }

    @Test fun strictCursorAndOrdinaryReadBindingsCannotBeSubstituted() {
        val value = cursor()
        assertNotEquals(LocalMasterChangesBindingV1.digest(listOf(value)), LocalMasterChangesBindingV1.readProjectionDigest(value))
    }

    @Test fun noDataOutcomesCannotCarryAReadProjection() {
        val read = LocalMasterChangesBindingV1.readProjectionDigest(cursor())
        for (result in LocalMasterChangesRpcAccessResultV1.entries.filter { it != LocalMasterChangesRpcAccessResultV1.ALLOWED }) {
            assertFailsWith<IllegalArgumentException> { LocalMasterChangesRpcAuthorizationV1(result, null, read) }
            assertNull(LocalMasterChangesRpcAuthorizationV1(result, null).readProjectionBinding)
        }
        // A legacy strict-only observation cannot silently acquire a READ binding.
        assertNull(LocalMasterChangesRpcAuthorizationV1(LocalMasterChangesRpcAccessResultV1.ALLOWED,
            LocalMasterChangesBindingV1.digest(listOf(cursor()))).readProjectionBinding)
    }
}
