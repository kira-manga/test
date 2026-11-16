package com.ukkera.pos.app.gateway.supporting

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlin.test.*

/** Self-authored, NOT independent. Routing support for frozen LAN-A01/B03/D10
 * (qa/pos/designs/lan-master-client-v1/cases.json); no live task/source/grant is minted. */
class LanKitchenRoutingSupportingTest {
    @Test fun broadKitchenUsesTheExistingStationValidators() {
        val station = DomainId.random()
        requireLanBusinessWorkflow(LocalWorkflowQuery(LocalWorkflow.KITCHEN, food = LocalFoodUiQuery.StationTasks(station)))
        assertFails { requireLanBusinessWorkflow(LocalWorkflowQuery(LocalWorkflow.KITCHEN, food = LocalFoodUiQuery.MyChecks())) }
        for ((action, nativeAction) in listOf(LocalAction.FOOD_STATION_START to LocalFoodStationActionV1.START,
                LocalAction.FOOD_STATION_READY to LocalFoodStationActionV1.READY)) {
            val body = LocalFoodRequestV1.Station(DomainId.random(), Revision(1), DomainId.random(), Revision(1), station, nativeAction)
            val request = MutationRequest(OperationId.random(), LocalFoodProfileV1.COMMAND, LocalFoodCodecV1.encode(body))
            requireLanBusinessMutation(action, request)
            val normalized = request.copyForSupportingStock(body)
            requireLanBusinessMutation(action, normalized)
            val opposite = if (action == LocalAction.FOOD_STATION_START) LocalAction.FOOD_STATION_READY else LocalAction.FOOD_STATION_START
            assertFails { requireLanBusinessMutation(opposite, request) }
            assertFails { requireLanBusinessMutation(action, MutationRequest(request.operationId, request.command,
                LocalFoodCodecV1.encode(body.copy(confirmationId = DomainId.random())))) }
        }
    }

    private fun MutationRequest.copyForSupportingStock(body: LocalFoodRequestV1.Station): MutationRequest =
        MutationRequest(operationId, LocalFoodStockCodecV2.COMMAND,
            LocalFoodStockCodecV2.encode(LocalFoodStockRequestV2.Preparation(body)))
}
