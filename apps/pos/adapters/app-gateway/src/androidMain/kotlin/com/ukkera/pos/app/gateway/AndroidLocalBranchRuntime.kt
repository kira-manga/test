package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.LocalEnrollmentMode
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.LocalEnrollmentUiGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.sales.store.AndroidPhysicalCompositionNamespaceV1
import com.ukkera.pos.sales.store.TransactionRetailJournalParticipantsSource
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.readOriginalBusinessPublications
import com.ukkera.pos.security.readOriginalPurchasingPolicies
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext


/**
 * Android same-device original client: the shared [LocalCohostBranchRuntime] over AndroidKeyStore
 * journal custody and the genuine Android retail physical connection.
 */
public class AndroidLocalBranchRuntime private constructor(
    original: AndroidOriginalLocalClient,
    journals: AndroidSameCohostClientJournals,
    retailPhysical: LocalRetailPhysicalBinding?,
    publications: LocalCompanyBusinessPublications?,
) : LocalCohostBranchRuntime(original, journals, retailPhysical, publications) {
    override fun toString(): String = "AndroidLocalBranchRuntime([REDACTED])"

    public companion object {
        /** Concrete registered local services, not the previous installation-only empty set. */
        public suspend fun openOriginalMaster(application: Application,
            context: SelfOwnedLocalSecurityContext,
            trainingComposition: Boolean = false): AndroidSelfOwnedMasterOpenResult {
            check(trainingComposition == (context.compositionProfile.mode ==
                com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING))
            val publications = when (val read = context.readOriginalBusinessPublications()) {
                is LocalCompanyAuthorityResult.Value -> read.value
                is LocalCompanyAuthorityResult.Unavailable -> null
            }
            val signatures = publications?.let { NativeCompanyPublicationSignatureVerifier(it.trust) }
                ?: UnavailableCompanyPublicationSignatureVerifier
            // Acquire from this same original native owner before Room/history opening.
            // Missing support stays unavailable; signed-policy retention is not receiving
            // qualification and cannot manufacture product/document authority.
            val purchasingPolicyTrust = when (val read = context.readOriginalPurchasingPolicies()) {
                is LocalCompanyAuthorityResult.Value -> read.value.trust
                is LocalCompanyAuthorityResult.Unavailable -> null
            }
            // The same genuine physical/retail stack serves both compositions. Its native
            // directory and Keystore namespace come from the exact context profile, not a
            // caller flag, so a training master can never acquire a live spool/catalogue.
            val composition = AndroidPhysicalCompositionNamespaceV1.of(context.compositionProfile)
            check((composition.mode == com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) == trainingComposition)
            val physical = AndroidOriginalPhysicalJournalExecutionGate.create(application, composition)
            val retailParticipants: TransactionRetailJournalParticipantsSource =
                physical as TransactionRetailJournalParticipantsSource
            val commerce = LocalBranchCommerceRegistry(signatures, UnavailableSalesReturnPolicy,
                UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
                retailParticipants = retailParticipants,
                purchasingPolicyTrust = purchasingPolicyTrust,
                nativePublications = publications)
            return AndroidSelfOwnedMasterService.fromOriginalContext(application, context, commerce.commands,
                commerce.reads, physical = physical, nativeOriginalImports = commerce.nativeOriginalImports,
                stockProjection = commerce.stockProjection, historyProducer = commerce.historyProducer)
        }

        /** Called by the retained platform owner, never by the sign-in form or a serialized body. */
        public suspend fun connectOriginal(application: Application, master: AndroidSelfOwnedMasterService,
            action: LocalOriginalClientJournalAction,
            trainingComposition: Boolean = false): AndroidLocalBranchRuntime? {
            currentCoroutineContext().ensureActive()
            val original = master.originalLocalClient()
            check(trainingComposition == (original.compositionMode ==
                com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING))
            val physicalGate = original.physicalRecordingGate() as? AndroidOriginalPhysicalJournalExecutionGate
            check(physicalGate == null || physicalGate.ownsComposition(original.compositionMode))
            if (trainingComposition) check(physicalGate != null &&
                physicalGate.composition.mode == com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING)
            val publications = original.originalBusinessPublications()
            // Physical enrollment never creates/replaces a missing client journal. A missing
            // physical catalogue keeps only that capability unavailable; existing nonphysical
            // login/reads still use their independently valid original journals.
            val retailPhysical = AndroidOriginalRetailPhysicalConnection.acquire(application, original, action)
            val acquired = AndroidOriginalClientJournals.acquire(application, original, action)
            if (acquired !is AndroidOriginalClientJournalsResult.Opened) return null
            var candidate: AndroidLocalBranchRuntime? = null
            var delivered = false
            try {
                currentCoroutineContext().ensureActive()
                check(original.isCurrent())
                val journals = AndroidSameCohostClientJournals(application, original, acquired.journals)
                val runtime = AndroidLocalBranchRuntime(original, journals, retailPhysical, publications)
                    .also { candidate = it }
                check(runtime.isCurrent())
                return runtime.also { delivered = true }
            } finally {
                if (!delivered) withContext(NonCancellable) {
                    val actual = candidate
                    if (actual == null) acquired.journals.close() else actual.close()
                }
            }
        }
    }
}
