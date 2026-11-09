package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.inventory.store.LocalStockCompositeVerifier
import com.ukkera.pos.sales.store.LocalFoodFinancialProducer
import com.ukkera.pos.sales.store.LocalFoodFinancialProfileV1

/**
 * Companion of the SAME normalized stock repository. A financial command name or detached
 * originalIssue is not a stock producer proof: validate its complete genuine food source and
 * effect set, then the real company signature and exact selected recipe snapshot bindings.
 * Install additively beside the preparation and purchasing verifiers, never in their place.
 */
public class LocalCatalogFoodFinancialVerifier(private val signatures: CompanyPublicationSignatureVerifier) : LocalStockCompositeVerifier {
    override val commands: Set<CommandKey> = setOf(LocalFoodFinancialProfileV1.COMMAND)
    override fun requireComplete(evidence: RetainedCommandEvidence) {
        LocalFoodFinancialProducer.requireComplete(evidence).forEach { requireSignedFoodSnapshot(it, evidence.scope, signatures) }
    }
}
