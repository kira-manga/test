package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer

/** Committed ordinary LEGACY stock.opening.post/1 and purchasing.receipt.post/1 history.
 * The installed receipt program replays over core's genuine native BEFORE cut; core then
 * compares the captured requirement and every literal effect/resource. Rejections, serial,
 * purchase-order, normalized and native-original profiles stay unsupported (held). */
public class LocalLegacyStockReceiptHistoryProducer(
    repository: LocalStockRepository,
    authority: LocalStockAuthorityReader,
) : RuntimeHistoryBusinessProducer {
    private val handlers: Map<CommandKey, StockReceiptHandler> = listOf(
        StockReceiptHandler(repository, authority, opening = true),
        StockReceiptHandler(repository, authority, opening = false),
    ).associateBy { it.key }
    override val commands: Set<CommandKey> = handlers.keys.toSet()

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val handler = handlers[command.command] ?: return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!original.committed || original.code != "committed" || original.nativeOriginalProvenance != null ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        handler.requireHistoricalLegacyReceipt(command)
        return true
    }
    public suspend fun requireOriginal(command: com.ukkera.pos.database.BusinessOriginalProgram) {
        val original = command.evidence
        check(original.committed && original.code == "committed" && original.nativeOriginalProvenance == null)
        (handlers[command.command] ?: error("Original legacy receipt profile unavailable")).requireOriginalLegacyReceipt(command)
    }

}
