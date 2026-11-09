package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Each displayed body is ONE actual public response, never a merged read cut or cached permission. */
internal suspend fun foodWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    val selected = query.food ?: invalid()
    if (selected is LocalFoodUiQuery.TableAssignment) return foodTableAssignmentWorkflow(access, selected)
    if (selected is LocalFoodUiQuery.FloorDirectory || selected is LocalFoodUiQuery.FloorTables ||
        selected is LocalFoodUiQuery.TableDefinition) return foodFloorWorkflow(access, selected)
    if (selected.isFinancial()) return foodFinancialWorkflow(access, selected)
    val reply: ReadReply.Data
    val body: LocalFoodUiBody
    val next: LocalFoodUiQuery?
    val forms: List<LocalActionForm>
    when (selected) {
        is LocalFoodUiQuery.MyChecks -> {
            val cut = foodRead(access, LocalFoodQueryV1.Own(selected.after))
            reply = cut.reply
            body = LocalFoodUiBody.Checks(cut.result.orders.map(::foodCheck))
            next = cut.result.nextAfter?.let { selected.copy(after = it) }
            forms = if (access.installed.isFoodTableAssignmentsOnly) emptyList() else listOfNotNull(foodCreateDraftForm(access))
        }
        is LocalFoodUiQuery.Check -> {
            val cut = foodRead(access, LocalFoodQueryV1.Order(selected.orderId, selected.afterPortionId))
            reply = cut.reply
            val detail = cut.result.detail ?: foodMissing()
            val check = LocalFoodUiBody.Check(foodCheck(detail.summary), detail.portions.map(::foodPortion),
                detail.originalQuotedTotal, detail.chargeResolutionRequired, detail.financialHandoff?.let(::foodFinancialHandoff),
                cut.kitchen?.let { LocalFoodUiKitchenObservation(it.orderId, it.orderStorageRevision, it.unsentPortionCount,
                    LocalFoodUiKitchenStatus.valueOf(it.status.name)) },
                cut.delivery?.let { LocalFoodUiDeliveryObservation(it.orderId, it.orderStorageRevision,
                    LocalFoodUiDeliveryDetails(it.details.recipient, it.details.contact, it.details.address)) },
                cut.currentUnpaidPayable,
                tableAssignmentAvailable = access.installed.has(LocalFoodTableAssignmentProfileV1.COMMAND) &&
                    access.installed.has(LocalFoodTableAssignmentReadCodecV1.QUERY) &&
                    access.allow(LocalAction.FOOD_ASSIGN_TABLE, detail.summary.orderId),
                currentUnpaidPayableProfile = cut.currentUnpaidPayableProfile)
            body = check
            next = cut.result.nextAfter?.let { selected.copy(afterPortionId = it) }
            forms = if (access.installed.isFoodTableAssignmentsOnly) emptyList()
                else foodCheckForms(access, check) + listOfNotNull(foodBeginCollectionForm(access, check))
        }
        is LocalFoodUiQuery.Menu -> {
            val cut = foodMenuRead(access, LocalFoodMenuQueryV1(after = selected.after))
            reply = cut.reply
            val menu = LocalFoodUiBody.Menu(cut.page.catalogRevision, cut.page.recipes.map { recipe ->
                LocalFoodUiRecipe(recipe.recipeId, recipe.recipeRevision, recipe.outputSkuId, recipe.portionQuantity,
                    recipe.active, recipe.choices.map { LocalFoodUiChoice(it.choiceId,
                        LocalText(it.names.english, it.names.arabic), it.modifierIds.toList()) })
            })
            body = menu
            next = cut.page.nextAfter?.let { selected.copy(after = it) }
            // The optional add target is a selector from the previously displayed owner read,
            // NOT part of this catalog cut. First staging re-reads that exact owner generation.
            val quotationInstalled = selected.orderId != null &&
                access.allow(LocalAction.FOOD_ADD, selected.orderId) && foodQuotationInstallationAvailable(access)
            forms = listOfNotNull(foodMenuForm(access, selected, menu, quotationInstalled))
        }
        is LocalFoodUiQuery.ConfiguredStations, is LocalFoodUiQuery.StationLabel -> {
            val stationQuery: LocalFoodStationDirectoryQueryV1 = when (selected) {
                is LocalFoodUiQuery.ConfiguredStations -> LocalFoodStationDirectoryQueryV1.Configured(selected.after)
                is LocalFoodUiQuery.StationLabel -> LocalFoodStationDirectoryQueryV1.Station(selected.stationId)
                else -> error("food.station.selector")
            }
            reply = access.read(LocalFoodStationDirectoryCodecV1.read(stationQuery))
            val value = LocalFoodStationDirectoryCodecV1.result(reply.payload)
            value.unavailable?.let { reason -> throw GatewayFailure(when (reason) {
                LocalFoodStationDirectoryUnavailableV1.TARGET_UNAVAILABLE -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
                LocalFoodStationDirectoryUnavailableV1.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
            }) }
            if (selected is LocalFoodUiQuery.StationLabel && (value.nextAfter != null ||
                    value.stations.singleOrNull()?.stationId != selected.stationId)) foodMissing()
            if (selected is LocalFoodUiQuery.ConfiguredStations)
                foodPageCoordinates(value.stations.map { it.stationId }, selected.after, value.nextAfter, 20)
            body = LocalFoodUiBody.Stations(value.stations.map { LocalFoodUiStation(it.stationId,
                LocalText(it.names.english, it.names.arabic), it.active, LocalFoodDeliveryMode.valueOf(it.mode.name),
                it.configurationStorageRevision) })
            next = (selected as? LocalFoodUiQuery.ConfiguredStations)?.let { original ->
                value.nextAfter?.let { original.copy(after = it) }
            }
            forms = emptyList()
        }
        is LocalFoodUiQuery.StationTasks, is LocalFoodUiQuery.Task -> {
            val stationQuery: LocalFoodQueryV1 = when (selected) {
                is LocalFoodUiQuery.StationTasks -> LocalFoodQueryV1.Station(selected.stationId, selected.afterTaskId)
                is LocalFoodUiQuery.Task -> LocalFoodQueryV1.Task(selected.stationId, selected.taskId)
                else -> error("food.task.selector")
            }
            val cut = foodRead(access, stationQuery)
            reply = cut.reply
            val tasks = LocalFoodUiBody.Tasks(cut.result.tasks.map(::foodTask))
            body = tasks
            next = (selected as? LocalFoodUiQuery.StationTasks)?.let { original ->
                cut.result.nextAfter?.let { original.copy(afterTaskId = it) }
            }
            forms = if (selected is LocalFoodUiQuery.Task) foodTaskForms(access, tasks.tasks.single()) else emptyList()
        }
        is LocalFoodUiQuery.Recipients -> {
            val cut = foodDirectory(access, LocalFoodHandoffQueryV2.Recipients(selected.orderId, selected.expectedOrderStorageRevision, selected.after))
            reply = cut.reply
            val value = cut.result
            if (value.incoming.isNotEmpty() || value.nextIncoming != null || value.recipients.any { it.actorId == access.view.actorId }) unavailable()
            // This directory may advance over filtered employees without a visible recipient.
            // Its continuation is not required to equal the last visible item or imply a full page.
            if (value.recipients.size > 20 || value.recipients.map { it.actorId }.distinct().size != value.recipients.size ||
                value.nextRecipient != null && value.nextRecipient == selected.after) unavailable()
            body = LocalFoodUiBody.Recipients(selected.orderId, selected.expectedOrderStorageRevision,
                value.recipients.map { LocalFoodUiRecipient(it.actorId, it.label) }, value.financial?.let(::foodFinancialHandoff))
            next = value.nextRecipient?.let { selected.copy(after = it) }
            forms = listOfNotNull(foodOfferSelectionForm(access, selected.orderId, selected.expectedOrderStorageRevision,
                value.financial?.let(::foodFinancialHandoff)))
        }
        is LocalFoodUiQuery.Incoming -> {
            val cut = foodDirectory(access, LocalFoodHandoffQueryV2.Incoming(selected.cursor?.let { LocalFoodHandoffCursorV2(it.phase, it.after) }))
            reply = cut.reply
            val value = cut.result
            if (value.recipients.isNotEmpty() || value.nextRecipient != null || value.incoming.any { it.recipientId != access.view.actorId }) unavailable()
            body = LocalFoodUiBody.Incoming(value.incoming.map { offer ->
                LocalFoodUiIncomingOffer(LocalFoodUiCheck(offer.orderId, offer.fromActorId, offer.orderStorageRevision,
                    offer.activity.name, foodService(offer.service), offer.portionCount, offer.handoffId, offer.recipientId), offer.offeredAt,
                    offer.financial?.let(::foodFinancialHandoff))
            }, cursorExpired = value.unavailable == LocalFoodHandoffDirectoryUnavailableV2.CURSOR_EXPIRED)
            next = value.nextIncoming?.let { selected.copy(cursor = LocalFoodUiHandoffCursor(it.phase, it.after)) }
            forms = emptyList() // An inbox summary is not the full responsibility review required for acceptance.
        }
        is LocalFoodUiQuery.IncomingKnownOrder -> {
            // No header-only acceptance fallback. Always read the complete genuine current
            // responsibility; a selected inbox generation may not silently become a new offer.
            requireFoodResponsibilityInstalled(access)
            val handoffId = selected.handoffId ?: foodRead(access, LocalFoodQueryV1.Handoff(selected.orderId))
                .result.orders.singleOrNull()?.handoffId ?: foodMissing()
            val cut = foodResponsibility(access, selected.orderId, handoffId, selected.expectedOrderStorageRevision)
            reply = cut.reply
            val value = cut.responsibility
            body = LocalFoodUiBody.Handoff(LocalFoodUiCheck(value.orderId, value.oldOwnerId, value.orderStorageRevision,
                value.activity.name, foodService(value.service), value.portions.size, value.handoffId, value.recipientId),
                value.portions.map { LocalFoodUiPortion(it.portionId, it.description, it.quantity, "",
                    it.preparationDomainRevision, LocalFoodPreparation.valueOf(it.preparation.name),
                    LocalFoodFulfillment.valueOf(it.fulfillment.name), it.startedWork, it.cancellationRequested) },
                value.originalQuotedTotal, value.chargeResolutionRequired, value.financial?.let(::foodFinancialHandoff))
            next = null
            forms = if (access.installed.has(LocalFoodProfileV1.COMMAND) && access.allow(LocalAction.FOOD_ACCEPT_HANDOFF, value.orderId))
                listOf(form(LocalAction.FOOD_ACCEPT_HANDOFF, foodOrderFields(value.orderId, value.orderStorageRevision) +
                    idField(LocalField.HANDOFF_ID, value.handoffId) + foodHandoffFinancialFields(value.financial?.let(::foodFinancialHandoff)),
                    foodHandoffNotice())) else emptyList()
        }
        is LocalFoodUiQuery.Collection, is LocalFoodUiQuery.Receipt, is LocalFoodUiQuery.Refund,
        is LocalFoodUiQuery.Claim, is LocalFoodUiQuery.OwnClaims, is LocalFoodUiQuery.PaymentOriginal,
        is LocalFoodUiQuery.PaymentAttempt, is LocalFoodUiQuery.OwnPendingPayments -> error("food.financial.routed_above")
        is LocalFoodUiQuery.TableAssignment -> error("food.table.assignment.routed_above")
        is LocalFoodUiQuery.FloorDirectory, is LocalFoodUiQuery.FloorTables,
        is LocalFoodUiQuery.TableDefinition -> error("food.floor.routed_above")
    }
    val page = LocalFoodUiPage(selected, access.view.scope.companyId, access.view.scope.branchId,
        access.view.actorId, reply.branchRevision, reply.observedAt, body, next)
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = page)
}

internal data class FoodDirectoryCut(val reply: ReadReply.Data, val result: LocalFoodHandoffDirectoryResultV2)
internal suspend fun foodDirectory(access: GatewayAccess, query: LocalFoodHandoffQueryV2): FoodDirectoryCut {
    if (!access.installed.has(LocalFoodHandoffProfileV2.QUERY)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val reply = access.read(LocalFoodHandoffCodecV2.read(query))
    val value = LocalFoodHandoffCodecV2.result(reply.payload)
    value.unavailable?.let { reason ->
        if (reason != LocalFoodHandoffDirectoryUnavailableV2.CURSOR_EXPIRED || query !is LocalFoodHandoffQueryV2.Incoming)
            throw GatewayFailure(when (reason) {
                LocalFoodHandoffDirectoryUnavailableV2.TARGET_UNAVAILABLE, LocalFoodHandoffDirectoryUnavailableV2.CURSOR_EXPIRED -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
                LocalFoodHandoffDirectoryUnavailableV2.NOT_PRECOLLECTION -> LocalServiceProblem.FOOD_NOT_PRECOLLECTION
                LocalFoodHandoffDirectoryUnavailableV2.PAYMENT_UNRESOLVED -> LocalServiceProblem.FOOD_PAYMENT_UNRESOLVED
                LocalFoodHandoffDirectoryUnavailableV2.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
            })
    }
    return FoodDirectoryCut(reply, value)
}

internal data class FoodReadCut(val reply: ReadReply.Data, val result: LocalFoodReadResultV1,
    val kitchen: LocalFoodCheckKitchenObservationV1? = null,
    val delivery: LocalFoodDeliveryObservationV1? = null,
    val currentUnpaidPayable: Money? = null,
    val currentUnpaidPayableProfile: LocalFoodUiCurrentUnpaidPayableProfile = LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY) {
    val kitchenUnavailableForUnsentWork: Boolean get() =
        kitchen?.status == LocalFoodCheckKitchenStatusV1.UNAVAILABLE && kitchen.unsentPortionCount > 0
}
internal suspend fun foodRead(access: GatewayAccess, query: LocalFoodQueryV1): FoodReadCut {
    // Select the highest installed version before the single protected read. Failure or
    // a null amount from that version never selects a lower version or historical amount.
    // The check and its observations must come from this single actual protected reply.
    val request = if (query is LocalFoodQueryV1.Order) when {
        access.installed.has(LocalFoodCheckKitchenCodecV3.QUERY) -> LocalFoodCheckKitchenCodecV3.read(query)
        access.installed.has(LocalFoodCheckKitchenCodecV2.QUERY) -> LocalFoodCheckKitchenCodecV2.read(query)
        access.installed.has(LocalFoodCheckKitchenCodecV1.QUERY) -> LocalFoodCheckKitchenCodecV1.read(query)
        else -> LocalFoodReadCodecV1.read(query)
    } else LocalFoodReadCodecV1.read(query)
    val reply = access.read(request)
    val cut = when (request.query) {
        LocalFoodCheckKitchenCodecV3.QUERY -> LocalFoodCheckKitchenCodecV3.result(reply.payload).let {
            FoodReadCut(reply, it.food, it.kitchen, it.delivery, it.currentUnpaidPayable,
                LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3)
        }
        LocalFoodCheckKitchenCodecV2.QUERY -> LocalFoodCheckKitchenCodecV2.result(reply.payload).let {
            FoodReadCut(reply, it.food, it.kitchen, it.delivery, it.currentUnpaidPayable)
        }
        LocalFoodCheckKitchenCodecV1.QUERY -> LocalFoodCheckKitchenCodecV1.result(reply.payload).let {
            FoodReadCut(reply, it.food, it.kitchen, it.delivery)
        }
        else -> FoodReadCut(reply, LocalFoodReadCodecV1.result(reply.payload))
    }
    val result = cut.result
    val kitchen = cut.kitchen
    val delivery = cut.delivery
    result.unavailable?.let { reason -> throw GatewayFailure(when (reason) {
        LocalFoodReadUnavailableV2.TARGET_UNAVAILABLE -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
        LocalFoodReadUnavailableV2.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
    }) }
    when (query) {
        is LocalFoodQueryV1.Own -> {
            if (result.detail != null || result.tasks.isNotEmpty() || result.orders.any { it.ownerId != access.view.actorId }) unavailable()
            foodPageCoordinates(result.orders.map { it.orderId }, query.after, result.nextAfter, query.limit)
        }
        is LocalFoodQueryV1.Order -> {
            if (result.orders.isNotEmpty() || result.tasks.isNotEmpty()) unavailable()
            val detail = result.detail ?: foodMissing()
            if (detail.summary.orderId != query.orderId || detail.summary.ownerId != access.view.actorId ||
                detail.portions.any { it.preparation.portionId != it.portionId }) unavailable()
            if ((detail.summary.service.channel == LocalSalesChannel.DELIVERY) != (delivery != null)) unavailable()
            delivery?.let { observation ->
                if (observation.orderId != detail.summary.orderId ||
                    observation.orderStorageRevision != detail.summary.revision) unavailable()
            }
            foodCheck(detail.summary)
            foodPageCoordinates(detail.portions.map { it.portionId }, query.afterPortionId, result.nextAfter, query.limit)
            kitchen?.let { observation ->
                val visibleUnsent = detail.portions.count { it.preparation.status == com.ukkera.pos.sales.FoodPreparationStatus.NOT_SENT }
                if (observation.orderId != detail.summary.orderId || observation.orderStorageRevision != detail.summary.revision ||
                    observation.unsentPortionCount < visibleUnsent ||
                    observation.unsentPortionCount > detail.summary.portionCount - (detail.portions.size - visibleUnsent) ||
                    observation.status == LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK && observation.unsentPortionCount != 0) unavailable()
            }
        }
        is LocalFoodQueryV1.Handoff -> {
            if (result.detail != null || result.tasks.isNotEmpty() || result.nextAfter != null) unavailable()
            val header = result.orders.singleOrNull() ?: foodMissing()
            if (header.orderId != query.orderId || header.intendedRecipientId != access.view.actorId || header.handoffId == null) foodMissing()
        }
        is LocalFoodQueryV1.Station -> {
            if (result.detail != null || result.orders.isNotEmpty() || result.tasks.any { it.stationId != query.stationId }) unavailable()
            foodPageCoordinates(result.tasks.map { it.taskId }, query.afterTaskId, result.nextAfter, query.limit)
        }
        is LocalFoodQueryV1.Task -> {
            if (result.detail != null || result.orders.isNotEmpty() || result.nextAfter != null) unavailable()
            val task = result.tasks.singleOrNull() ?: foodMissing()
            if (task.stationId != query.stationId || task.taskId != query.taskId) unavailable()
        }
    }
    result.tasks.forEach { if (it.deliveryEvidence != "DURABLE_INTENT_NOT_PHYSICAL_ACK") unavailable() }
    return cut
}

internal data class FoodMenuCut(val reply: ReadReply.Data, val page: LocalFoodMenuPageV1)
internal suspend fun foodMenuRead(access: GatewayAccess, query: LocalFoodMenuQueryV1): FoodMenuCut {
    val reply = access.read(LocalFoodMenuCodecV1.read(query))
    val value = LocalFoodMenuCodecV1.result(reply.payload)
    if (query.recipeId == null) foodPageCoordinates(value.recipes.map { it.recipeId }, query.after, value.nextAfter, query.limit)
    else if (value.nextAfter != null || value.recipes.size > 1 || value.recipes.any { it.recipeId != query.recipeId }) unavailable()
    if (value.recipes.any { it.choices.size !in 1..8 || it.choices.map { choice -> choice.choiceId }.distinct().size != it.choices.size }) unavailable()
    return FoodMenuCut(reply, value)
}

internal data class FoodResponsibilityCut(val reply: ReadReply.Data, val responsibility: LocalFoodHandoffResponsibilityV1)
internal fun requireFoodResponsibilityInstalled(access: GatewayAccess) {
    if (!access.installed.has(LocalFoodHandoffResponsibilityCodecV1.QUERY)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}
internal suspend fun foodResponsibility(access: GatewayAccess, orderId: DomainId, handoffId: DomainId,
    expected: Revision? = null): FoodResponsibilityCut {
    requireFoodResponsibilityInstalled(access)
    val reply = access.read(LocalFoodHandoffResponsibilityCodecV1.read(LocalFoodHandoffResponsibilityQueryV1(orderId, handoffId, expected)))
    val value = LocalFoodHandoffResponsibilityCodecV1.result(reply.payload)
    value.unavailable?.let { reason -> throw GatewayFailure(when (reason) {
        LocalFoodHandoffUnavailableV1.TARGET_UNAVAILABLE -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
        LocalFoodHandoffUnavailableV1.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
        LocalFoodHandoffUnavailableV1.NOT_PRECOLLECTION -> LocalServiceProblem.FOOD_NOT_PRECOLLECTION
        LocalFoodHandoffUnavailableV1.PAYMENT_UNRESOLVED -> LocalServiceProblem.FOOD_PAYMENT_UNRESOLVED
    }) }
    val responsibility = value.responsibility ?: foodMissing()
    if (responsibility.orderId != orderId || responsibility.handoffId != handoffId || responsibility.recipientId != access.view.actorId ||
        expected != null && responsibility.orderStorageRevision != expected) foodMissing()
    return FoodResponsibilityCut(reply, responsibility)
}

internal fun foodPageCoordinates(ids: List<DomainId>, after: DomainId?, next: DomainId?, maximum: Int) {
    if (ids.size > maximum || ids.distinct().size != ids.size || ids != ids.sortedBy { it.value } ||
        ids.any { after != null && it.value <= after.value } || next != null && (ids.isEmpty() || next != ids.last())) unavailable()
}
internal fun foodMissing(): Nothing = throw GatewayFailure(LocalServiceProblem.FOOD_TARGET_UNAVAILABLE)
private fun foodService(service: LocalFoodServiceV1): LocalFoodUiService =
    LocalFoodUiService(service.channel.name, service.reference, service.seatNote)
internal fun foodCheck(value: LocalFoodOrderSummaryV1): LocalFoodUiCheck =
    LocalFoodUiCheck(value.orderId, value.ownerId, value.revision, value.activity.name, foodService(value.service),
        value.portionCount, value.handoffId, value.intendedRecipientId, value.workClosed, value.financialCollectionId)
internal fun foodFinancialHandoff(value: LocalFoodHandoffFinancialV1): LocalFoodUiFinancial =
    LocalFoodUiFinancial(value.expectation.collectionId, value.expectation.expectedCollectionRevision,
        value.status.name, value.collected, value.remaining, value.refundDue, value.pendingClaimCount, value.paymentUnresolved)
private fun foodPortion(value: LocalFoodPortionViewV1): LocalFoodUiPortion = LocalFoodUiPortion(value.portionId,
    value.description, value.quantity, value.itemNote, value.preparation.revision,
    LocalFoodPreparation.valueOf(value.preparation.status.name), LocalFoodFulfillment.valueOf(value.preparation.fulfillment.name),
    value.preparation.started, value.preparation.cancellationReasonId != null, value.lineId, value.seatNote)
internal fun foodTask(value: LocalFoodStationTaskViewV1): LocalFoodUiTask = LocalFoodUiTask(value.orderId, value.orderRevision,
    value.portionId, value.portionRevision, value.taskId, value.stationId, value.description, value.quantity,
    value.itemNote, value.serviceReference, LocalFoodTaskState.valueOf(value.taskStatus.name),
    LocalFoodPreparation.valueOf(value.preparationStatus.name), LocalFoodDeliveryMode.valueOf(value.deliveryMode.name), value.seatNote)
