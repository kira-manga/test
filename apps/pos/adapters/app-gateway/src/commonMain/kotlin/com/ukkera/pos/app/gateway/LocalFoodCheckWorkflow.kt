package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodMenuQueryV1
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency

/** Closed owner/menu/check projection. Broader FOOD forms are never constructed or filtered. */
internal suspend fun foodCheckWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    requireFoodCheckWorkflow(query)
    val selected = query.food ?: invalid()
    val reply: ReadReply.Data
    val body: LocalFoodUiBody
    val next: LocalFoodUiQuery?
    val forms: List<LocalActionForm>
    when (selected) {
        is LocalFoodUiQuery.MyChecks -> {
            val cut = foodRead(access, LocalFoodQueryV1.Own(selected.after))
            reply = cut.reply
            if (cut.result.orders.any { it.revision.value == 0L }) unavailable()
            body = LocalFoodUiBody.Checks(cut.result.orders.map(::foodCheck))
            next = cut.result.nextAfter?.let { selected.copy(after = it) }
            forms = listOfNotNull(foodCreateDraftForm(access)?.also {
                if (it.action != LocalAction.FOOD_CREATE_DRAFT || it.version != 2) unavailable()
            })
        }
        is LocalFoodUiQuery.Check -> {
            val cut = foodRead(access, LocalFoodQueryV1.Order(selected.orderId, selected.afterPortionId))
            reply = cut.reply
            val detail = cut.result.detail ?: foodMissing()
            val kitchen = cut.kitchen ?: unavailable()
            if (detail.originalQuotedTotal.currency != Currency.EGP || detail.originalQuotedTotal.minor < 0) unavailable()
            // An actual empty owned check has no unsent work. A malformed or absent
            // advertised observation is not converted to a successful empty projection.
            if (kitchen.unsentPortionCount == 0 && kitchen.status != LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK)
                unavailable()
            body = LocalFoodUiBody.Check(foodCheck(detail.summary), detail.portions.map(::foodCheckPortion),
                detail.originalQuotedTotal, detail.chargeResolutionRequired,
                detail.financialHandoff?.let(::foodFinancialHandoff),
                LocalFoodUiKitchenObservation(kitchen.orderId, kitchen.orderStorageRevision, kitchen.unsentPortionCount,
                    LocalFoodUiKitchenStatus.valueOf(kitchen.status.name)),
                cut.delivery?.let { observation -> LocalFoodUiDeliveryObservation(observation.orderId,
                    observation.orderStorageRevision, LocalFoodUiDeliveryDetails(observation.details.recipient,
                        observation.details.contact, observation.details.address)) },
                cut.currentUnpaidPayable, tableAssignmentAvailable = false,
                currentUnpaidPayableProfile = cut.currentUnpaidPayableProfile)
            next = cut.result.nextAfter?.let { selected.copy(afterPortionId = it) }
            forms = emptyList() // Add is reviewed through an exact menu target, never a Send/service/financial form.
        }
        is LocalFoodUiQuery.Menu -> {
            val cut = foodMenuRead(access, LocalFoodMenuQueryV1(after = selected.after))
            reply = cut.reply
            val menu = LocalFoodUiBody.Menu(cut.page.catalogRevision, cut.page.recipes.map { recipe ->
                LocalFoodUiRecipe(recipe.recipeId, recipe.recipeRevision, recipe.outputSkuId, recipe.portionQuantity,
                    recipe.active, recipe.choices.map { choice -> LocalFoodUiChoice(choice.choiceId,
                        LocalText(choice.names.english, choice.names.arabic), choice.modifierIds.toList()) })
            })
            body = menu
            next = cut.page.nextAfter?.let { selected.copy(after = it) }
            // This target is a selector, not part of the catalog response cut or an owner
            // grant. First Stage separately rereads its genuine exact order generation.
            val canSelectQuotedAdd = selected.orderId != null && access.allow(LocalAction.FOOD_ADD, selected.orderId) &&
                foodQuotationInstallationAvailable(access)
            forms = if (!canSelectQuotedAdd) emptyList() else listOfNotNull(
                foodMenuForm(access, selected, menu, quotationInstalled = true)?.also {
                    if (it.action != LocalAction.FOOD_ADD || it.version != 3) unavailable()
                })
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    access.checkCurrent()
    val page = LocalFoodUiPage(selected, access.view.scope.companyId, access.view.scope.branchId,
        access.view.actorId, reply.branchRevision, reply.observedAt, body, next)
    return LocalWorkflowPage(LocalWorkflow.FOOD, emptyList(), forms, food = page)
}

/** Fresh preparation only. Saved Submit/Lookup never invokes quotation discovery or selection. */
internal suspend fun prepareFoodCheckIntent(access: GatewayAccess, intent: LocalCommandIntent): PreparedLocalIntent {
    requireFoodCheckIntent(intent)
    if (!access.installed.has(LocalFoodStockCodecV2.COMMAND)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val request = when (intent.action) {
        LocalAction.FOOD_CREATE_DRAFT -> prepareFoodDraft(access, intent)
        LocalAction.FOOD_ADD -> {
            if (!foodQuotationInstallationAvailable(access)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            val values = InputValues(intent.values)
            val orderId = values.id(LocalField.ORDER_ID)
            val selected = parseFoodSelection(access, LocalAction.FOOD_ADD, orderId, values, intent.lineField,
                intent.lines, preserveInputOrder = true).request as? LocalFoodRequestV1.Add ?: invalid()
            values.finish()
            LocalFoodStockCodecV2.command(intent.operationId, LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(selected))
        }
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    val original = foodCheckRequest(intent.action, request)
    access.checkCurrent()
    return PreparedLocalIntent(request, foodActionLabel(intent.action), foodIntentSummary(intent,
        (original as? LocalFoodStockRequestV2.ActivityProtected)?.request))
}

internal suspend fun previewFoodCheckSelection(access: GatewayAccess, input: LocalFoodPreviewInput): LocalFoodPricePreview {
    requireFoodCheckPreview(input)
    // Preview success, a menu or a connection never selects a downgraded Add format.
    if (!foodQuotationInstallationAvailable(access)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return previewLocalFoodSelection(access, input)
}

private fun foodCheckPortion(value: LocalFoodPortionViewV1): LocalFoodUiPortion = LocalFoodUiPortion(value.portionId,
    value.description, value.quantity, value.itemNote, value.preparation.revision,
    LocalFoodPreparation.valueOf(value.preparation.status.name), LocalFoodFulfillment.valueOf(value.preparation.fulfillment.name),
    value.preparation.started, value.preparation.cancellationReasonId != null, value.lineId, value.seatNote)
