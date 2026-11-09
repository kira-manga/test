package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the exact frozen late-refresh-v3 packet and the machine data used by the executor.
 * It checks the twelve documented static-checker coverage gaps as executable invariant
 * assertions; it does not run application behavior.
 */
public class LateRefreshV3FixtureIntegrityTest {
    private val fixtures = LateRefreshV3Fixtures

    @Test
    public fun frozenPacketAndCountsAreExact() {
        assertEquals(4, fixtures.cases.size)
        assertEquals(9, fixtures.variants.size)
        assertEquals(listOf("E3LR-L1", "E3LR-L2", "E3LR-L3", "E3LR-L4"),
            fixtures.cases.map { it.caseId })
        assertEquals(7, fixtures.variants.values.count { it.expectedClass == "REFRESH_REFUSED_ACCOUNTED" })
        assertEquals(2, fixtures.variants.values.count { it.expectedClass == "REFRESH_SAME_ORIGINAL" })
        assertEquals(6, fixtures.variants.values.sumOf { it.alternateOutcomes.size })
        assertEquals(10, fixtures.variants.values.sumOf { it.branchOutcomes.size })
        assertEquals(1, fixtures.variants.values.count { it.optionalBoundaryDiagnostic })
        fixtures.variants.values.forEach { variant ->
            assertEquals("NOT RUN", variant.status)
            assertEquals("new", variant.disposition)
            assertEquals("REFRESH", variant.family)
            assertFalse(variant.checkpointForced)
            assertTrue(variant.checkpointOptional)
            assertEquals("readOriginalSetup", variant.checkpointOptionalRead)
        }
    }

    @Test
    public fun unchangedProfileNoChangeAndNoAfterChangeRead() {
        val unchanged = fixtures.recordProfile("LR-REC-UNCHANGED")
        assertFalse(unchanged.requiredBoolean("read_back_required"))
        assertTrue(unchanged.requiredBoolean("read_back_forbidden"))
        assertFalse(unchanged.requiredBoolean("late_read_after_change_required"))
        assertFalse(unchanged.requiredBoolean("production_write"))
        assertTrue(unchanged.stringList("required_observations").isEmpty())
        assertTrue(unchanged.stringList("forbidden_observations").containsAll(listOf(
            "MUTATION_READ_BACK_RECORDED", "THROW_RECORDED", "LATE_RECORD_READ_AFTER_CHANGE",
        )))
        val classes = unchanged.requiredObject("allowed_expected_by_kind").mapValues {
            it.value.toString().trim('"')
        }
        assertEquals(mapOf(
            "LATE_LIFECYCLE" to "REFRESH_REFUSED_ACCOUNTED",
            "LATE_LIFECYCLE_FOREIGN" to "REFRESH_REFUSED_ACCOUNTED",
            "LATE_MODE_DIAGNOSTIC" to "REFRESH_REFUSED_ACCOUNTED",
            "CONTROL_HELD" to "REFRESH_SAME_ORIGINAL",
            "CONTROL_BRANCHED" to "REFRESH_SAME_ORIGINAL",
        ), classes)
        val noChange = fixtures.lifecycleProfile("LR-LIFE-NO-CHANGE")
        assertEquals("SAME_ORIGINAL_SINGLE", noChange.requiredString("final_publication"))
        assertEquals(listOf("OLD_ONLY", "REFRESH_ONLY"),
            noChange.stringList("checkpoint_publication_state_primary_domain"))
        assertEquals("NOT_APPLICABLE", noChange.requiredString("no_refresh_republish"))
        assertEquals("RETAIN_OR_CONFIRMED_REPLACEMENT_SINGLE_LIVE",
            noChange.requiredString("publication_reuse_rule"))
    }

    @Test
    public fun checkerGapInvariantsArePresent() {
        val l1 = fixtures.variants.getValue("E3LR-L1.V01-LATE-STOP")
        val l2 = fixtures.variants.getValue("E3LR-L2.V01-LATE-WATCHDOG-WITHDRAWAL-UNIT")
        val l3 = fixtures.variants.getValue("E3LR-L3.V01-LATE-MODE-POLICY-HELD")
        val l4v01 = fixtures.variants.getValue("E3LR-L4.V01-UNCHANGED-CONTROL-SETUP-HELD")
        val l4v02 = fixtures.variants.getValue("E3LR-L4.V02-UNCHANGED-CONTROL-SETUP-NOT-ENTERED")

        assertTrue(l1.requiredObservations.contains("NO_RESURRECTION"))
        assertTrue(l2.requiredObservations.contains("NO_RESURRECTION"))
        assertTrue(l3.requiredObservations.contains("NO_RESURRECTION"))
        assertEquals("OBSERVED_LIVE_TOKEN_AT_CHECKPOINT",
            l2.raw.requiredObject("checkpoint_publication_state_rule")
                .requiredString("exact_owner_source"))
        assertTrue(l2.raw.requiredObject("late_change").requiredBoolean("exact_owner_required"))
        assertEquals("RETAIN_FOREIGN_NO_WITHDRAWAL_NO_REPLACEMENT",
            fixtures.variants.getValue("E3LR-L2.V03-LATE-FOREIGN-PUBLICATION")
                .raw.requiredObject("watchdog_withdrawal")
                .requiredString("foreign_publication_rule"))

        val l2None = l2.branchOutcomes.single { it.requiredString("branch_id") == "NO_LIVE_PUBLICATION_TOKEN" }
        assertTrue(l2None.stringList("forbidden_observations").containsAll(listOf(
            "WATCHDOG_WITHDRAWAL_EXACT_OWNER", "WATCHDOG_WITHDRAWAL_OUTCOME_RECORDED",
            "WATCHDOG_ATTRIBUTION_RECORDED",
        )))
        val l2Overlap = l2.branchOutcomes.single {
            it.requiredString("branch_id") == "CHECKPOINT_PUBLICATION_OVERLAP"
        }
        assertTrue(l2Overlap.requiredBoolean("single_live_rule_failure"))
        assertTrue(l2Overlap.requiredBoolean("no_normalization"))

        val l4v02Entered = l4v02.branchOutcomes.single {
            it.requiredString("branch_id") == "SETUP_READ_ENTERED"
        }
        assertTrue(l4v02Entered.stringList("required_observations").containsAll(listOf(
            "CURRENTNESS_TRUE", "PUBLICATION_SINGLE_LIVE", "MASTER_OPERATIONAL",
        )))
        assertEquals("RETAINED_OR_REPLACEMENT_SINGLE_LIVE",
            l4v02Entered.requiredString("publication_expectation"))

        val l4v02NotEntered = l4v02.branchOutcomes.single {
            it.requiredString("branch_id") == "SETUP_READ_NOT_ENTERED"
        }
        assertEquals("RETAINED_OR_REPLACEMENT_SINGLE_LIVE",
            l4v02NotEntered.requiredString("publication_expectation"))
        assertTrue(l4v02NotEntered.stringList("required_observations").containsAll(listOf(
            "LATE_SETUP_BOUNDARY_NOT_ENTERED_OBSERVED", "SETUP_RELEASE_NOT_APPLICABLE_RECORDED",
        )))
        assertTrue(l4v02NotEntered.stringList("forbidden_observations").containsAll(listOf(
            "LATE_SETUP_READ_ENTERED", "LATE_SETUP_HELD", "LATE_SETUP_RELEASE_RESULT_RECORDED",
            "NULLABLE_NOT_A_GATE",
        )))

        val l4v01Branch = l4v01.branchOutcomes.single {
            it.requiredString("branch_id") == "SETTLED_WHILE_SETUP_HELD"
        }
        assertEquals("NOT_A_PASS_CLASS", l4v01Branch.requiredString("expected_class"))
        assertTrue(l4v01Branch.requiredObject("final_connected").requiredBoolean("no_late_coverage"))
        assertTrue(l4v01Branch.stringList("forbidden_observations").containsAll(listOf(
            "POSITIVE_LATE_CONTROL_CLAIM", "LATE_SETUP_RELEASE_RESULT_RECORDED",
        )))

        val l1Throw = fixtures.variants.getValue("E3LR-L1.V03-LATE-RECORD-READ-THROW")
        assertTrue(l1Throw.forbiddenObservations.contains("MUTATION_READ_BACK_RECORDED"))
        assertFalse(l1Throw.raw.requiredObject("late_change").requiredBoolean("read_back_required"))
        assertTrue(l1Throw.raw.requiredObject("late_change").requiredBoolean("read_back_forbidden"))
        assertTrue(l1Throw.requiredObservations.containsAll(listOf("THROW_RECORDED", "NO_RETRY")))

        val alternate = l1.alternateOutcomes.single()
        val mustNotClaim = alternate.stringList("must_not_claim")
        val required = alternate.stringList("required_observations")
        assertTrue(mustNotClaim.intersect(required.toSet()).isEmpty())
        assertTrue(mustNotClaim.containsAll(listOf(
            "LATE_SETUP_READ_ENTERED", "LATE_CHANGE_APPLIED", "WATCHDOG_WITHDRAWAL_EXACT_OWNER",
        )))
        assertTrue(mustNotClaim.containsAll(listOf(
            "MUTATION_READ_BACK_RECORDED", "THROW_RECORDED", "LATE_RECORD_READ_AFTER_CHANGE",
        )))
        val l2Alternate = l2.alternateOutcomes.single()
        assertTrue(l2Alternate.stringList("forbidden_observations").containsAll(listOf(
            "MUTATION_READ_BACK_RECORDED", "THROW_RECORDED", "LATE_RECORD_READ_AFTER_CHANGE",
        )))
    }
}
