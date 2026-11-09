package com.ukkera.pos.app.gateway.setup.independent.pairedactorintentdisposition

import com.ukkera.pos.app.gateway.setup.LocalSetupLanSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PAD-S002/S003 and the PAD-S004 construction guard only. No interactive review, native target,
 * actor-source installation, old-owner retirement or late-result publication is exercised.
 */
class PairedActorIntentLanSettingsSupportingTest {
    private val frozen = PairedActorIntentDispositionFrozenInputs

    @Test fun s002AllNineExplicitPairsAreCapturedWithoutRecoupling() {
        for (pair in frozen.pairs) {
            val input = frozen.input(pair)
            val captured = LocalSetupLanSettings.from(input)
            assertEquals(pair.deviceAction, input.credentialAction, pair.id)
            assertEquals(pair.actorAction, input.actorIntentAction, pair.id)
            assertPair(pair, captured, pair.id)
            val retried = LocalSetupLanSettings.from(frozen.input(pair))
            assertTrue(captured.sameSelection(retried), "${pair.id}/unchanged retry")
            assertPair(pair, captured, "${pair.id}/retained")
        }
    }

    @Test fun s002EveryFrozenComparisonIncludesBothOriginalActions() {
        val captured = frozen.pairs.associate { it.id to LocalSetupLanSettings.from(frozen.input(it)) }
        for (row in frozen.comparisons) {
            val left = captured.getValue(row.left.id)
            val right = captured.getValue(row.right.id)
            assertEquals(row.expectedSame, left.sameSelection(right), row.id)
            assertPair(row.left, left, "${row.id}/left retained")
            assertPair(row.right, right, "${row.id}/right retained")
        }
        // This includes PAD-S006's eight changed-pair comparisons. It does not execute its
        // delayed-result/retirement schedule or qualify zero late adoption/publication.
    }

    @Test fun s003AllLegacyOmissionsMatchOnlyTheirExplicitlyCoupledPair() {
        for (row in frozen.legacy) {
            val input = frozen.legacyInput(row.deviceAction)
            val captured = LocalSetupLanSettings.from(input)
            assertEquals(row.deviceAction, input.credentialAction, row.id)
            assertEquals(row.expectedActorAction, input.actorIntentAction, row.id)
            assertPair(row.expectedPair, captured, row.id)
            val explicit = LocalSetupLanSettings.from(frozen.input(row.expectedPair))
            assertTrue(captured.sameSelection(explicit), "${row.id}/explicit coupled")
            for (different in frozen.pairs.filter {
                it.deviceAction == row.deviceAction && it.actorAction != row.expectedActorAction
            }) {
                val changed = LocalSetupLanSettings.from(frozen.input(different))
                assertPair(different, changed, "${row.id}/${different.id}/explicit override")
                assertFalse(captured.sameSelection(changed), "${row.id}/${different.id}/different actor")
                assertPair(row.expectedPair, captured, "${row.id}/${different.id}/original retained")
            }
        }
    }

    @Test fun s004SettingsGuardRefusesAllIncompletePairsAndRetainsEveryCompletePair() {
        for (row in frozen.reviews) {
            // Explicit null is intentional. Omitting the additive argument is PAD-S003, not
            // evidence that an interactive actor choice was separately reviewed.
            val result = runCatching { LocalSetupLanSettings.from(frozen.input(row.deviceAction, row.actorAction)) }
            assertEquals(row.expectedComplete, result.isSuccess, "${row.id}/construction only")
            if (row.expectedComplete) {
                assertPair(checkNotNull(row.expectedPair), result.getOrThrow(), row.id)
            }
        }
    }

    private fun assertPair(pair: PairedActorIntentDispositionFrozenInputs.Pair,
        settings: LocalSetupLanSettings, id: String) {
        assertEquals(pair.deviceAction, settings.credentialAction, "$id/device action")
        assertEquals(pair.actorAction, settings.actorIntentAction, "$id/actor action")
    }
}
