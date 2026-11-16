package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.*

/** Self-authored, NOT independent. Purpose-specific replay DATA only. Actual native
 * activation/source/key/lineage and cold readiness are exercised by the separate genuine
 * FOOD scenario; a constructed protected image cannot supply those proofs. */
class PolicyActivationReplayImageSupportingTest {
    private val id = DomainId("00000000-0000-4000-8000-000000000001")
    private val digest = PayloadDigest("a".repeat(64))
    private val before = PayloadDigest("b".repeat(64))

    @Test fun policyAndOptionalAccountImagesRemainUpdatesWithTheirCompleteOriginalCommitments() {
        for ((protected, table) in listOf(ProtectedLocalTable.EFFECTIVE_POLICY to RuntimeHistoryTableV2.POLICY,
            ProtectedLocalTable.ACCOUNT to RuntimeHistoryTableV2.ACCOUNT)) {
            val original = LocalEmployeePolicyActivationProtectedImage(protected, id, Revision(2), before, digest)
            val replay = RuntimePolicyActivationReplayImage.capture(original)
            assertSame(original, replay.original)
            assertEquals(table, replay.table)
            assertEquals(RuntimeHistoryMutationKindV2.UPDATE, replay.mutation)
        }
    }

    @Test fun dedicatedActivationAuditIsTheOnlyInsertAndHasNoPreviousImage() {
        val original = LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.AUTH_EVENT, id, Revision(0), null, digest)
        val replay = RuntimePolicyActivationReplayImage.capture(original)
        assertSame(original, replay.original)
        assertEquals(RuntimeHistoryTableV2.AUTH_EVENT, replay.table)
        assertEquals(RuntimeHistoryMutationKindV2.INSERT, replay.mutation)
        assertFailsWith<IllegalArgumentException> {
            LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.AUTH_EVENT, id, Revision(1), null, digest)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalEmployeePolicyActivationProtectedImage(ProtectedLocalTable.EFFECTIVE_POLICY, id, Revision(0), before, digest)
        }
    }

    @Test fun ordinarySessionAuthenticationImageGrammarStillRefusesEffectivePolicy() {
        val scope = BranchScope(id, DomainId("00000000-0000-4000-8000-000000000002"),
            DomainId("00000000-0000-4000-8000-000000000003"))
        assertFalse(RuntimeHistoryTableV2.POLICY.hasAuthenticationImages)
        assertFails {
            RuntimeAuthenticationImageV1.capture(ProtectedLocalBinding(
                ProtectedLocalRecordKey.EffectivePolicy(scope, id), Revision(1)), digest)
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeAuthenticationImageV1(RuntimeHistoryTableV2.POLICY.tag, id, null, Revision(1), digest).validate()
        }
        assertEquals(RuntimeHistoryMutationKindV2.INSERT,
            RuntimeAuthenticationImageV1.capture(ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(scope, id), Revision(0)), digest).mutation)
    }
}
