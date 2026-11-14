package com.ukkera.pos.database

import com.ukkera.pos.security.*

/** Shared JVM/Android driver/builder adapters for shared original-history startup and owned Room open.
 * Native driver acquisition and DATABASE_BOUND remain on the same retained invocation.
 * Shared hold/ACK decisions never create a driver or grant another writer. */
internal object AndroidRuntimeHistoryStartup {
    sealed interface Selection {
        class Allocation(val original: OriginalNativeHistorySnapshot): Selection
        class Opened(val owner: RoomRuntimeHistoryOwner): Selection
        class AcknowledgementPending(val original: OriginalNativeHistorySnapshot): Selection
        class Held(val holds: Set<RuntimeHistoryReadHold>): Selection
    }
    suspend fun select(platform: SelfOwnedMasterPlatform,custody: SelfOwnedNativeHistoryCustody,
        producer: RuntimeHistoryBusinessProducer? = null, stockCodec: StockCanonicalProjectionV2? = null): Selection {
        return when(val selected=RuntimeHistoryStartup.select(custody,producer,stockCodec)) {
            is RuntimeHistoryStartup.Selection.Allocation -> Selection.Allocation(selected.original)
            is RuntimeHistoryStartup.Selection.Ready -> Selection.Opened(openDatabase(platform,custody,selected.admission))
            is RuntimeHistoryStartup.Selection.AcknowledgementPending -> Selection.AcknowledgementPending(selected.original)
            is RuntimeHistoryStartup.Selection.Held -> Selection.Held(selected.holds)
        }
    }
    suspend fun openFirst(platform: SelfOwnedMasterPlatform,custody: SelfOwnedNativeHistoryCustody): RoomRuntimeHistoryOwner {
        RuntimeHistoryStartup.requireFirstRoomOpen(custody)
        return openDatabase(platform,custody,null)
    }
    private suspend fun openDatabase(platform: SelfOwnedMasterPlatform,custody: SelfOwnedNativeHistoryCustody,
        admission: RoomOriginalHistoryAdmission?): RoomRuntimeHistoryOwner {
        try {
            return custody.runRoomWork(block = {
                try {
                    val driver=platform.nativeOwner.openRoomDriver(custody)
                    openOwnedRuntimeHistoryRoom(custody, admission, driver,
                        builder = { platform.databaseBuilder(driver.databasePath) },
                        finishNativeOpen = { platform.nativeOwner.finishRoomOpen(custody, driver) })
                } catch (failure: Throwable) {
                    parkRuntimeHistoryAdmissionDuringOpenFailure(custody, admission, failure)
                    throw failure
                }
            }, discard = { owner ->
                // The genuine constructor retained the sole actual Room close. This disposal
                // only denies output and parks independent DATA; terminal close must first
                // drain this very invocation and must never be awaited from its own body.
                owner.discardOpenedOutput()
            })
        } catch (failure: Throwable) {
            // runRoomWork has returned/failed here. Its original tasks and output disposal
            // remain native-owned; terminal cleanup may now await that actual drain safely.
            disposeRuntimeHistoryAdmissionAfterOpenFailure(custody, admission, failure)
            throw failure
        }
    }
}
