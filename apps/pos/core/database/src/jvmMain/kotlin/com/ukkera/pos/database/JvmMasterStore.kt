package com.ukkera.pos.database

import androidx.room3.Room
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

/**
 * Opens the bounded file-store profile. An unprovisioned file and the default trust
 * adapters cannot write. This does NOT provision identity, activate a copied database,
 * acquire an installed-service custody lock or claim OS-key qualification.
 */
public fun openLocalMasterStore(
    path: Path,
    scope: BranchScope,
    authority: AuthorityIdentity,
    handlers: List<MasterCommandHandler>,
    authorization: TransactionAuthorizationSource = UnavailableTransactionAuthorization,
    runtime: MasterRuntimeVerifier = UnqualifiedMasterRuntime,
    clock: MasterClock = MasterClock { UtcInstant(Clock.System.now()) },
    readHandlers: List<MasterReadHandler> = emptyList(),
): AtomicMasterStore {
    require(path.isAbsolute && path.fileName.toString().isNotBlank())
    require(path.fileName.toString() != ":memory:" && !Files.isSymbolicLink(path))
    require(Files.isDirectory(path.parent)) { "Provision the protected directory first" }
    requireLegacyMasterRoomProfile()
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
    return AtomicMasterStore(database, scope, authority, handlers, authorization, runtime, clock, readHandlers = readHandlers)
}
