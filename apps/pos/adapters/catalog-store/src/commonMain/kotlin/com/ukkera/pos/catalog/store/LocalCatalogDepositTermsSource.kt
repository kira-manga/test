package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.security.sha256
import com.ukkera.pos.sales.DepositTerms
import com.ukkera.pos.sales.PaidSale
import com.ukkera.pos.sales.store.LocalSalesPriceSourceV2
import com.ukkera.pos.sales.store.LocalSalesQuoteUnavailable
import com.ukkera.pos.sales.store.LocalSalesQuoteUnavailableReason
import com.ukkera.pos.sales.store.OriginalDepositTermsUnavailable
import com.ukkera.pos.sales.store.PreparedDepositTerms
import com.ukkera.pos.sales.store.TransactionDepositTermsSource
import com.ukkera.pos.types.Revision

/**
 * Container deposit terms (CAT-10) read only from the immutable, company-signed SKU publication
 * that priced the sold portion. The current catalog never substitutes: a missing, damaged or
 * unauthenticated publication throws, and a publication without a deposit yields none.
 */
public class LocalCatalogDepositTermsSource(
    private val signatures: CompanyPublicationSignatureVerifier,
) : TransactionDepositTermsSource {
    override public suspend fun terms(view: CommandReadView, sale: PaidSale, source: LocalSalesPriceSourceV2): PreparedDepositTerms? {
        check(source.scope == view.scope && sale.scope == view.scope) { "sales.deposit.scope" }
        val row = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.PUBLICATION, source.companyPublicationId))
            ?: throw OriginalDepositTermsUnavailable()
        requireOriginalDepositTerms(row.revision == Revision(1) &&
            row.schema == LocalConfigurationProfile.schema(LocalConfigurationProfile.PUBLICATION) &&
            row.digest == sha256(row.payload.copyBytes()))
        // Only detached content validation is translated. Database calls and the signature
        // adapter stay outside this catch so storage/cancellation/custody failures propagate.
        val record = try { decodeRecord(view.scope, row) }
            catch (_: IllegalArgumentException) { throw OriginalDepositTermsUnavailable() }
            catch (_: IllegalStateException) { throw OriginalDepositTermsUnavailable() }
        val signed = (record.body as? ConfigurationBody.Sku)?.value ?: throw OriginalDepositTermsUnavailable()
        val sku = signed.definition
        requireOriginalDepositTerms(sku.publicationId == source.companyPublicationId && sku.skuId == source.skuId &&
            sku.company == view.scope.company())
        requireOriginalDepositTerms(signatures.verify(sku.company, signed.signingKeyId,
            LocalConfigurationCodec.canonicalPublication(sku), signed.signatureHex))
        // Reuse the exact immutable publication -> retained installer join. Its bounded history
        // traversal works for signed absence too; this does not add a return promise/capture.
        val original = try { prepareRetailReturnPublication(view, record, signatures) }
            catch (unavailable: LocalSalesQuoteUnavailable) {
                if (unavailable.reason != LocalSalesQuoteUnavailableReason.ORIGINAL_PUBLICATION_UNAVAILABLE &&
                    unavailable.reason != LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED) throw unavailable
                throw OriginalDepositTermsUnavailable()
            }
        val deposit = sku.containerDeposit ?: return null
        return PreparedDepositTerms(DepositTerms(sku.skuId, sku.publicationId, deposit.amountPerContainer,
            deposit.liabilityMappingId, deposit.taxCategoryId), original.resources)
    }
}

private fun requireOriginalDepositTerms(valid: Boolean) {
    if (!valid) throw OriginalDepositTermsUnavailable()
}
