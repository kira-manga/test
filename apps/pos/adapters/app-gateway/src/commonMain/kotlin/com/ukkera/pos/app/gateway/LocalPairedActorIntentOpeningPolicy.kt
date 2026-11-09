package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction

/** Opening selection only. A classification is not authenticated allocation absence or custody. */
public enum class LocalPairedActorIntentOpeningDecision { ALLOCATE_NEW, OPEN_EXISTING, REFUSE }

/** Shared state rule; native adapters must supply a complete, authenticated original ledger. */
public object LocalPairedActorIntentOpeningPolicy {
    public fun decide(action: LocalPairedDeviceCredentialJournalAction, allocated: Boolean,
        allocationCount: Int, maximumAllocations: Int): LocalPairedActorIntentOpeningDecision {
        if (maximumAllocations < 1 || allocationCount !in 0..maximumAllocations ||
            allocated && allocationCount == 0) return LocalPairedActorIntentOpeningDecision.REFUSE
        return when (action) {
            LocalPairedDeviceCredentialJournalAction.ENROLL ->
                if (!allocated && allocationCount < maximumAllocations) LocalPairedActorIntentOpeningDecision.ALLOCATE_NEW
                else LocalPairedActorIntentOpeningDecision.REFUSE
            LocalPairedDeviceCredentialJournalAction.REOPEN,
            LocalPairedDeviceCredentialJournalAction.RECOVER ->
                if (allocated) LocalPairedActorIntentOpeningDecision.OPEN_EXISTING
                else LocalPairedActorIntentOpeningDecision.REFUSE
        }
    }
}
