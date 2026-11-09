package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.types.*

/** Exact company-price sidecars use the same complete decision as the live writer.
 * Every policy, branch, publication and high-water source comes from its already
 * joined earlier original; today's signature state never supplies historical trust. */
internal class LocalCompanyPriceHistory {
    suspend fun prepareOriginal(command: RuntimeHistoricalCommand, signed: LocalSignedSkuV1,
        previous: LocalPublishedSkuV1?): PreparedCompanyPriceHistory {
        val sku = signed.definition
        val sources = LocalPricingSourceReader.original(command)
        val index = sources.index()
        val append = sources.append(sku.skuId, LocalPricingIdentity.original(command))
        val hasPublications = index == null && sources.publicationPage(null).key != null
        val rules = (sku.prices + previous?.prices.orEmpty()).distinctBy { it.ruleId }.map { rule ->
            CompanyPriceHistoryRuleSources(rule.ruleId, sources.policy(sku.skuId, rule.ruleId), sources.branch(sku.skuId, rule.ruleId))
        }
        val prices = sku.prices.map { price ->
            CompanyPriceHistoryPriceSources(price, sources.version(sku.skuId, sku.publicationId, price),
                sources.high(sku.skuId, price.ruleId))
        }
        return prepareCompanyPriceHistory(command.scope, signed, previous,
            CompanyPriceHistoryInputs(index, append, hasPublications, rules, prices))
    }

    suspend fun joinCommitted(command: RuntimeHistoricalCommand, signed: LocalSignedSkuV1,
        previous: LocalPublishedSkuV1?, resources: MutableSet<RecoveryResourceKey>,
        claims: MutableList<ClaimChange>, changes: MutableList<AggregateChange>): Boolean {
        val prepared = prepareOriginal(command, signed, previous)
        if (prepared.rejection != null) return false
        resources += prepared.resources
        claims += prepared.claims
        // Both core-selected founding and enrolled employee originals require direct
        // authorization. This metadata is inert and cannot recreate AuthorizedAction.
        changes += prepared.changes(LocalPricingIdentity.original(command),
            LocalPricingActorAudit(command.actorId, command.policyRevision, null), command.recordedAt)
        return true
    }
}
