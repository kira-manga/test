package com.ukkera.pos.app

/** Ephemeral read selection, not a stored financial command or a permission grant. */
enum class LocalCashSection { MY_CASH, OWN_SESSION, CLOSED_REVIEW, TRANSFER, SAFE_BALANCE }

data class LocalCashUiQuery(
    val section: LocalCashSection = LocalCashSection.MY_CASH,
    /** Explicit actual disposition, never an instruction to move cash after an uncertain result. */
    val closeToSafe: Boolean = false,
    /** Read-only immutable count history; changing this never submits or acknowledges cash. */
    val showCounts: Boolean = false,
)
