package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Disposable SQL/transaction fixture, not a production original-history opener.
 *
 * These existing fixtures supply controlled authorization/runtime observations and seed
 * synthetic rows directly. Keep their SQL guards and durable writer profile while avoiding
 * the retired legacy production opener, which correctly refuses the owned schema. This
 * builder has no migration, native owner, history admission or Master authority claim.
 */
internal fun buildControlledMasterRoomFixture(
    builder: RoomDatabase.Builder<MasterRoomDatabase>,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): MasterRoomDatabase = buildDurableRoom(
    builder.addCallback(ProtectedLocalSchemaCallback)
        .addCallback(LocalAccessIssuanceSchemaCallback)
        .addCallback(MasterReplacementGuardsCallback)
        .addCallback(LocalAuthenticationSchemaCallback)
        .addCallback(LocalActionApprovalSchemaCallback),
    driver,
)
