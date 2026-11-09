package com.ukkera.pos.recovery.store

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryTableV2

/**
 * A bounded read of the exact custodied SQLite transaction, not a path, SQL callback or native
 * authority. Rows are cleared after each callback; a verifier must not retain their byte arrays.
 * All tables, including unscoped history, must be traversed before verification may complete.
 */
public interface LocalBackupHistoryRead {
    public val scope: BranchScope
    public val profile: SqliteSnapshotProfile
    public val inspection: SnapshotInspection
    public suspend fun forEachRow(table: NativeHistoryTableV2, consume: suspend (NativeHistoryPhysicalRowV2) -> Unit)
}

/**
 * Mandatory schema7 native integration, with no accepting/default implementation. Verify the
 * complete immutable event/chunk/row-image lineage, retained command/fragment commitments,
 * protected plaintext and original native provenance under authorized recovery-key custody.
 * Unknown producers, incomplete traversal or unavailable source proof must throw. A copied SQL
 * head, a callback return or this interface is not writer admission or a recoverability receipt.
 */
public fun interface LocalBackupHistoryVerification {
    public suspend fun verify(snapshot: LocalBackupHistoryRead)
}
