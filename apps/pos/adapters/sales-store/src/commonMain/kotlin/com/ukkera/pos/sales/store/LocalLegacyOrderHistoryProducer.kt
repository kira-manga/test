package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer

/** Committed LEGACY sales.order.write/1 history over the same quote, fact reducers
 * and literal writers as the live handler. Rejections/native imports stay separate. */
public class LocalLegacyOrderHistoryProducer(quoteSource: TransactionSalesQuoteSource) : RuntimeHistoryBusinessProducer {
    private val handler = LocalOrderHandler(quoteSource)
    override val commands: Set<CommandKey> = setOf(LocalSalesStoreProfile.ORDER_COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command != LocalSalesStoreProfile.ORDER_COMMAND) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!original.committed || original.code != "committed" || original.nativeOriginalProvenance != null ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val normalized = command.normalized
        check(handler.normalize(normalized) == normalized &&
            com.ukkera.pos.database.semanticDigest(command.scope, command.actorId, command.command, normalized) == command.semanticDigest)
        val request = LocalOrderCommandCodec.decode(normalized)
        handler.requireOriginal(com.ukkera.pos.database.NativeBusinessOriginalProgram(command), request)
        return true
    }
    public suspend fun requireOriginal(command: com.ukkera.pos.database.BusinessOriginalProgram) {
        val original = command.evidence
        check(command.command == LocalSalesStoreProfile.ORDER_COMMAND && original.committed && original.code == "committed" &&
            original.nativeOriginalProvenance == null && handler.normalize(command.normalized) == command.normalized &&
            com.ukkera.pos.database.semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        handler.requireOriginal(command, LocalOrderCommandCodec.decode(command.normalized))
    }

}
