package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction

/** Exact ordinary normalized stock history: receiving/inspection, counts, transfers and
 * safety changes use the SAME complete installed producer as the live stock reader.
 * This grants no missing purchase/approval history, older uncaptured authority, native
 * physical outcome or migration. Core must close every original dependency independently. */
public class LocalStockHistoryProducer(private val stock: LocalStockRepository) : RuntimeHistoryBusinessProducer {
    override public val commands: Set<CommandKey> = setOf(LocalStockRelationalProfileV2.COMMAND)
    override public suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Streamed) return false
        val request = LocalStockRelationalCommandsV2.decode(command.normalized)
        localStockCheck(LocalStockRelationalCommandsV2.encode(request) == command.normalized)
        val binding = stockAdministrationBinding(request, command.operationId, command.semanticDigest)
        command.requireRequirement(AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), command.scope, binding))
        stock.requireHistoricalRelationalProducer(command)
        return true
    }
}
