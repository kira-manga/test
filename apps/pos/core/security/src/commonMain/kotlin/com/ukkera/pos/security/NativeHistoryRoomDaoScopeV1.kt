package com.ukkera.pos.security

/**
 * Bounded SQL compatibility DATA for Room3's single generated-DAO scope. This cannot create a
 * writer, authenticate an actor, settle a native child or establish an outer SQL disposition.
 * The native driver must separately retain the same foreground actor, invocation and frame.
 * A fresh instance belongs to one already-begun outer transaction and is discarded only after
 * its actual COMMIT/ROLLBACK returns. Failed SQL never calls [completed].
 */
public class NativeHistoryRoomDaoScopeV1 public constructor() {
    public enum class Action { ENTER, RELEASE, ROLLBACK }
    private enum class State { IDLE, OPEN, ROLLBACK_REQUIRED }
    private var state = State.IDLE

    /** Validate before preparing AND stepping. No state changes until the actual step returns. */
    public fun before(sql: String): Action? {
        val value = sql.trim().uppercase()
        val rootRollback = value == "ROLLBACK" || value == "ROLLBACK TRANSACTION"
        if (rootRollback) return null // Actual outer rollback remains possible from every state.
        check(state != State.ROLLBACK_REQUIRED) { "Original outer rollback required" }
        val action = when (value) {
            "SAVEPOINT '1'" -> Action.ENTER
            "RELEASE SAVEPOINT '1'" -> Action.RELEASE
            "ROLLBACK TRANSACTION TO SAVEPOINT '1'" -> Action.ROLLBACK
            else -> {
                check(!value.startsWith("SAVEPOINT") && !value.startsWith("RELEASE") &&
                    !value.startsWith("ROLLBACK")) { "Unsupported original DAO scope" }
                if (value in setOf("COMMIT", "COMMIT TRANSACTION", "END TRANSACTION")) {
                    check(state == State.IDLE) { "Original DAO scope still open" }
                }
                return null
            }
        }
        check(if (action == Action.ENTER) state == State.IDLE else state == State.OPEN) {
            "Original DAO scope order mismatch"
        }
        return action
    }

    /** Only a normally returned, non-row-producing step can complete a scope control action. */
    public fun completed(action: Action) {
        check(if (action == Action.ENTER) state == State.IDLE else state == State.OPEN) {
            "Original DAO scope completion mismatch"
        }
        state = when (action) {
            Action.ENTER -> State.OPEN
            Action.RELEASE -> State.IDLE
            // SQLite retains the rolled-back savepoint until the root ends. Never treat that
            // as free nested capacity, a successful DAO result or a native rollback receipt.
            Action.ROLLBACK -> State.ROLLBACK_REQUIRED
        }
    }
}
