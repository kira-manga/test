package com.ukkera.pos.security

/**
 * The additive Windows/macOS company workflow ledgers: the desktop (V2) at-rest custody of the same
 * four record ledgers the Android V1 company registry keeps beside its container (employee
 * enrollments, employee policy activations, device pairings, device admissions). Business
 * publication and serial custody have no desktop ledger yet and are not part of this closed set.
 */
internal enum class SelfOwnedCompanyLedgerKindV2(
    val ledgerDomain: String,
    val aadDomain: String,
    val purpose: String,
) {
    EMPLOYEE_ENROLLMENTS("native-company-employee-enrollment-ledger/v2",
        "native-company-employee-enrollment-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_EMPLOYEE_ENROLLMENTS_V2"),
    EMPLOYEE_POLICY_ACTIVATIONS("native-company-employee-policy-activation-ledger/v2",
        "native-company-employee-policy-activation-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_EMPLOYEE_POLICY_ACTIVATIONS_V2"),
    DEVICE_PAIRINGS("native-company-device-pairing-ledger/v2",
        "native-company-device-pairing-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_DEVICE_PAIRINGS_V2"),
    DEVICE_ADMISSIONS("native-company-device-admission-ledger/v2",
        "native-company-device-admission-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_DEVICE_ADMISSIONS_V2"),
}

/**
 * Desktop company ledger profile. At rest, each ledger is the closed V2 frame (profile, ledger
 * domain, version 2) around the shared record grammar of that ledger, sealed by the registry key
 * with the ledger's own V2 AAD (which embeds the complete company-registry AAD of the seven-key
 * bootstrap) and signed by the issuer key over `registrySignature(aad, nonce, ciphertext)` exactly
 * like the company container. No Android V1 ledger AAD, file or envelope is read or produced.
 *
 * Root endorsements are NOT at-rest custody: they are the shared, client-verified statements of the
 * original root (employee authority certificate, policy activation, one-device pairing, unsigned
 * pairing disposition). Their messages keep the shared protocol domains so that every client
 * verifies a desktop Master exactly as an Android Master; the root key signs only a message whose
 * leading profile/domain is in [ROOT_ENDORSEMENT_DOMAINS] ([requireRootEndorsementMessage]).
 */
internal object SelfOwnedNativeCompanyLedgerProfileV2 {
    const val LEDGER_ENVELOPE_DOMAIN: String = "native-company-ledger-envelope/v2"
    /** Ledger record grammar maximum (the shared ledgers' maximum) plus the V2 frame. */
    const val MAXIMUM_LEDGER_PLAINTEXT_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 1_024
    const val MAXIMUM_LEDGER_ENVELOPE_BYTES: Int = MAXIMUM_LEDGER_PLAINTEXT_BYTES + 1_024
    const val MAXIMUM_LEDGER_AAD_BYTES: Int = SelfOwnedNativeOriginalProfileV2.MAXIMUM_COMPANY_BOOTSTRAP_BYTES + 4_096

    /** Closed set of shared root-endorsement message domains (V1 legacy pairing and SDK-bound pairing). */
    val ROOT_ENDORSEMENT_DOMAINS: Set<String> = setOf(
        "company-root-full-employee-approval/v1",
        "company-root-complete-employee-policy-approval/v1",
        "original-root-head-approved-one-device-pairing/v1",
        "original-root-head-approved-one-device-pairing/v2",
        "original-root-explicit-known-unsigned-device-pairing-disposition/v1",
        "original-root-explicit-known-unsigned-device-pairing-disposition/v2",
    )
}

internal object SelfOwnedNativeCompanyLedgerWireV2 {
    /** Ledger AAD: ledger domain, purpose and the complete company-registry AAD of [company]. */
    fun aad(kind: SelfOwnedCompanyLedgerKindV2, company: LocalCompanyAuthorityBootstrapV2): ByteArray =
        frame(kind.aadDomain, SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_AAD_BYTES) {
            string(kind.purpose)
            val registry = SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company)
            try { field(registry) } finally { registry.fill(0) }
        }

    /** V2 frame around exactly the shared ledger record grammar bytes. */
    fun encodeLedger(kind: SelfOwnedCompanyLedgerKindV2, records: ByteArray): ByteArray =
        frame(kind.ledgerDomain, SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_PLAINTEXT_BYTES) { field(records) }

    /** The shared record grammar bytes of a canonical V2 ledger frame of exactly [kind]. */
    fun decodeLedger(kind: SelfOwnedCompanyLedgerKindV2, bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<ByteArray> =
        selfOwnedIdentityGuarded {
            val records = read(bytes, kind.ledgerDomain, SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_PLAINTEXT_BYTES) {
                field(SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_PLAINTEXT_BYTES)
            }
            val canonical = encodeLedger(kind, records)
            try { if (!canonical.contentEquals(bytes)) { records.fill(0); refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) } }
            finally { canonical.fill(0) }
            records
        }

    fun encodeEnvelope(value: SelfOwnedRegistryEnvelopeV2): ByteArray =
        frame(SelfOwnedNativeCompanyLedgerProfileV2.LEDGER_ENVELOPE_DOMAIN, SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_ENVELOPE_BYTES) {
            field(value.nonce); field(value.ciphertext); field(value.signature)
        }

    fun decodeEnvelope(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedRegistryEnvelopeV2> = selfOwnedIdentityGuarded {
        val value = read(bytes, SelfOwnedNativeCompanyLedgerProfileV2.LEDGER_ENVELOPE_DOMAIN,
            SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_ENVELOPE_BYTES) {
            val nonce = field(SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES)
            val ciphertext = field(SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_PLAINTEXT_BYTES + 16)
            val signature = field(SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_BYTES)
            if (nonce.size != SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES || ciphertext.size < 16 ||
                signature.size != SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_BYTES) {
                nonce.fill(0); ciphertext.fill(0); signature.fill(0)
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            }
            SelfOwnedRegistryEnvelopeV2(nonce, ciphertext, signature)
        }
        val canonical = encodeEnvelope(value)
        try { if (!canonical.contentEquals(bytes)) { value.clear(); refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) } }
        finally { canonical.fill(0) }
        value
    }

    /**
     * Platform signing guard: refuses (STATE_UNCERTAIN) unless [message] is a shared root-endorsement
     * message, i.e. its leading V1 profile and domain are in the closed endorsement set. A root
     * announcement, delegation, head enrollment or any V2 frame never passes.
     */
    fun requireRootEndorsementMessage(message: ByteArray) {
        val accepted = try {
            val reader = SelfOwnedIdentityReader(message)
            reader.string() == SelfOwnedInstallationProfileV1.ID &&
                reader.string() in SelfOwnedNativeCompanyLedgerProfileV2.ROOT_ENDORSEMENT_DOMAINS
        } catch (_: SelfOwnedIdentityRefuse) { false }
        if (!accepted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    private fun frame(domain: String, maximum: Int, body: SelfOwnedWireWriterV1.() -> Unit): ByteArray =
        SelfOwnedInstalledIdentityWireV2.frame(SelfOwnedNativeOriginalProfileV2.ID, domain, maximum) {
            int(SelfOwnedNativeOriginalProfileV2.FORMAT_VERSION); body()
        }

    private inline fun <T> read(bytes: ByteArray, domain: String, maximum: Int, body: SelfOwnedIdentityReader.() -> T): T {
        if (bytes.size > maximum) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedNativeOriginalProfileV2.ID) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        if (reader.string() != domain) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        if (reader.int() != SelfOwnedNativeOriginalProfileV2.FORMAT_VERSION) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        val result = reader.body()
        reader.finish()
        return result
    }
}
