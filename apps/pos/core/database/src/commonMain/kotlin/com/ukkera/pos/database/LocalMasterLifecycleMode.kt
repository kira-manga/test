package com.ukkera.pos.database

import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityView

/** Shared lifecycle/progress only; native ownership and authoritative role checks remain mandatory. */
public enum class LocalMasterLifecycleMode {
    ALLOCATION_ONLY, INSTALLING, INSTALLATION_ACK_PENDING, INSTALLED_RUNTIME_HELD, OPERATIONAL, POLICY_HELD, UNRESOLVED, CLOSING, CLOSED,
}
public sealed interface LocalMasterInstallationOutcome {
    public class Installed internal constructor(public val receipt: LocalBranchInstallationReceiptV2): LocalMasterInstallationOutcome
    /** Original SQL/native completion exists on this owner, but no complete ACK was delivered. */
    public data object AcknowledgementPending: LocalMasterInstallationOutcome
    public data object Busy: LocalMasterInstallationOutcome
    public data object Unresolved: LocalMasterInstallationOutcome
}

/** Private setup-host data, not a session, a verifier or permission to publish business ingress. */
public class LocalOriginalInstallationSetup internal constructor(
    public val company: LocalCompanyAuthorityView,
    public val definition: LocalBranchInstallationSnapshotV2?,
    /** Settled original data; use the ACK observation to distinguish PENDING from CONFIRMED. */
    public val receipt: LocalBranchInstallationReceiptV2?,
) {
    override fun toString(): String = "LocalOriginalInstallationSetup([REDACTED])"
}

/** Saved native original ACK fact only; never current custody, runtime/history admission or business authority. */
public enum class LocalOriginalAcknowledgementStatus { PENDING, CONFIRMED }

public sealed interface LocalOriginalAcknowledgementRead {
    public class Observed internal constructor(
        public val status: LocalOriginalAcknowledgementStatus,
        public val receipt: LocalBranchInstallationReceiptV2,
    ) : LocalOriginalAcknowledgementRead {
        override fun toString(): String = "LocalOriginalAcknowledgementRead.Observed([REDACTED])"
    }
    public data object Busy : LocalOriginalAcknowledgementRead
    /** No conclusive native observation; never evidence of absence or failed installation. */
    public data object Unresolved : LocalOriginalAcknowledgementRead
}
