package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey

/** After the SAME complete installed stock producer, derive its live plan's commit deadline
 * from genuine admitted originals and native BEFORE sources. No caller deadline, replayed
 * command, reconstructed current authority or recordedAt substitution can satisfy this check. */
internal suspend fun requireStockAdministrationHistoryDeadline(command: RuntimeHistoricalCommand,
    stock: LocalStockRelationalInspection, repository: LocalStockRepository) {
    localStockCheck(command.command == LocalStockRelationalProfileV2.COMMAND &&
        command.operationId == stock.operationId && command.normalized == stock.original.normalized)
    val request = LocalStockRelationalCommandsV2.decode(command.normalized)
    if (request is LocalStockRelationalCommand.Receive || request is LocalStockRelationalCommand.ReceivePo)
        requireReceivingArrivalBeforeV2(command, request)
    val deadline = when (request) {
        is LocalStockRelationalCommand.Receive, is LocalStockRelationalCommand.ReceivePo ->
            retainedReceivingStockDeadline(stock, repository.receivingAuthority(), request)
        is LocalStockRelationalCommand.Inspect ->
            retainedInspectionStockDeadline(stock, repository.receivingAuthority(), request)
        is LocalStockRelationalCommand.CaptureCount, is LocalStockRelationalCommand.PostCount ->
            retainedCountStockDeadline(stock, repository.receivingAuthority(), request)
        is LocalStockRelationalCommand.Transfer ->
            retainedTransferStockDeadline(stock, repository.receivingAuthority(), request)
        is LocalStockRelationalCommand.InitializeFresh, is LocalStockRelationalCommand.Recall,
        is LocalStockRelationalCommand.Expire -> null
    }
    command.requireFinalDeadlineDecision(deadline)
}

/** Older receiving originals retain their literal v1-only source frames. Missing
 * additive frames do not prove v2 absence: compare the real native BEFORE cut,
 * independently of which negative-source profile the original captured. Never
 * inspect present-day custody or rewrite an earlier receipt's effects/resources. */
private suspend fun requireReceivingArrivalBeforeV2(command: RuntimeHistoricalCommand,
    request: LocalStockRelationalCommand) {
    suspend fun absent(key: AggregateKey, claim: ClaimKey) {
        localStockCheck(command.aggregateSource(key) == null && command.claim(claim) == null,
            LocalStockFailure.SOURCE_ALREADY_USED)
    }
    for (line in stockReceivingInput(request).lines) {
        absent(arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, line.sourceId), arrivalSourceClaimV2(line.sourceId))
        localStockCheck(command.claim(arrivalLineClaimV2(line.lineId)) == null, LocalStockFailure.SOURCE_ALREADY_USED)
        line.serialId?.let { absent(arrivalSerialIdKeyV2(it), arrivalSerialIdClaimV2(it)) }
        line.serialCode?.let { absent(arrivalSerialCodeKeyV2(it), arrivalSerialCodeClaimV2(it)) }
        line.lotId?.let { lot -> val key = arrivalLotKeyV2(line.skuId, lot); absent(key, arrivalLotClaimV2(key)) }
    }
}
