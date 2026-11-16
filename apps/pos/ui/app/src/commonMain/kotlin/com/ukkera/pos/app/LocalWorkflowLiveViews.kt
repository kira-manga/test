package com.ukkera.pos.app

import kotlinx.coroutines.flow.Flow

/** Optional protected read capability. Updates cannot stage, dispatch or acknowledge work. */
interface LocalWorkflowLiveViews {
    fun readUpdates(query: LocalWorkflowQuery): Flow<LocalWorkflowReadUpdate>
    /** Negative cleanup only; never erases pending operations or physical evidence. */
    suspend fun invalidateReadViews()
}

sealed interface LocalWorkflowReadUpdate {
    data class Available(val page: LocalWorkflowPage) : LocalWorkflowReadUpdate
    data class Unavailable(val problem: LocalServiceProblem) : LocalWorkflowReadUpdate
    /** A later branch cut cannot silently continue a previous paginated snapshot. */
    data object RestartRequired : LocalWorkflowReadUpdate
}

/** Read selection only. This never changes a staged body or a physical operation identity. */
fun LocalWorkflowQuery.rootReadSelection(): LocalWorkflowQuery = copy(cursor = null,
    branchReport = branchReport?.copy(after = null, expectedBranchRevision = null),
    food = when (val selected = food) {
        is LocalFoodUiQuery.FloorDirectory -> selected.copy(after = null)
        is LocalFoodUiQuery.FloorTables -> selected.copy(after = null)
        is LocalFoodUiQuery.MyChecks -> selected.copy(after = null)
        is LocalFoodUiQuery.OwnClaims -> selected.copy(after = null)
        is LocalFoodUiQuery.OwnPendingPayments -> selected.copy(after = null)
        is LocalFoodUiQuery.Check -> selected.copy(afterPortionId = null)
        is LocalFoodUiQuery.Menu -> selected.copy(after = null)
        is LocalFoodUiQuery.ConfiguredStations -> selected.copy(after = null)
        is LocalFoodUiQuery.StationTasks -> selected.copy(afterTaskId = null)
        is LocalFoodUiQuery.Recipients -> selected.copy(after = null)
        is LocalFoodUiQuery.Incoming -> selected.copy(cursor = null)
        // A known-order expected revision is a source guard, not a pagination token.
        else -> selected
    })
