package com.ukkera.pos.app.gateway.setup.independent.pairedactorintentdisposition

import com.ukkera.pos.app.gateway.LocalPairedActorIntentOpeningDecision
import com.ukkera.pos.app.gateway.LocalPairedActorIntentOpeningPolicy
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** PAD-S001 classification only. No native ledger, authority, resource or persistence is supplied. */
class PairedActorIntentOpeningPolicySupportingTest {
    @Test fun s001EnrollUsesEveryFrozenPresenceAndCapacityTuple() {
        classify(LocalPairedDeviceCredentialJournalAction.ENROLL)
    }

    @Test fun s001ReopenUsesEveryFrozenPresenceAndCapacityTuple() {
        classify(LocalPairedDeviceCredentialJournalAction.REOPEN)
    }

    @Test fun s001RecoverUsesEveryFrozenPresenceAndCapacityTuple() {
        classify(LocalPairedDeviceCredentialJournalAction.RECOVER)
    }

    private fun classify(action: LocalPairedDeviceCredentialJournalAction) {
        for (row in PairedActorIntentDispositionFrozenInputs.classifications.filter { it.action == action }) {
            val decision = LocalPairedActorIntentOpeningPolicy.decide(row.action, row.allocated,
                row.allocationCount, row.maximumAllocations)
            assertEquals(row.expected, semantics(decision, row.action), row.id)
        }
    }

    private fun semantics(decision: LocalPairedActorIntentOpeningDecision,
        action: LocalPairedDeviceCredentialJournalAction): String = when (decision) {
        LocalPairedActorIntentOpeningDecision.ALLOCATE_NEW -> "ALLOCATE_NEW_ORIGINAL"
        LocalPairedActorIntentOpeningDecision.REFUSE -> "REFUSE"
        LocalPairedActorIntentOpeningDecision.OPEN_EXISTING -> when (action) {
            LocalPairedDeviceCredentialJournalAction.REOPEN -> "ATTEMPT_ORIGINAL_REOPEN"
            LocalPairedDeviceCredentialJournalAction.RECOVER -> "ATTEMPT_ORIGINAL_RECOVER"
            LocalPairedDeviceCredentialJournalAction.ENROLL -> error("Existing opening cannot implement selected ENROLL")
        }
    }
}
