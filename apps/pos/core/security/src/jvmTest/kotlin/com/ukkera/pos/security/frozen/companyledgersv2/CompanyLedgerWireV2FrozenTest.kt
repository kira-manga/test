package com.ukkera.pos.security.frozen.companyledgersv2

import com.ukkera.pos.security.NativeEmployeeEnrollmentWireV1
import com.ukkera.pos.security.SelfOwnedCompanyLedgerKindV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedNativeCompanyLedgerWireV2
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV2
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.androidv1baseline.DocumentedLayout
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as F
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Frozen expectations for the additive Windows/macOS company workflow ledger custody (WP-C4),
 * authored on 13 November 2026 BEFORE the codec existed. Expected bytes are written from the
 * documented layout (4-byte big-endian length + UTF-8 strings, 4-byte ints, format version int 2
 * after the domain), never rendered by the code under test. Synthetic DATA only.
 * NOT independent (same author, owner instruction).
 */
class CompanyLedgerWireV2FrozenTest {
    private val profile = "ukkera.installation/self-owned-native-original/v2"

    private fun header(domain: String) = DocumentedLayout().string(profile).string(domain).int(2)

    private fun <T> data(result: SelfOwnedInstalledIdentityResultV2<T>): T =
        assertIs<SelfOwnedInstalledIdentityResultV2.Data<T>>(result).value

    private fun refused(result: SelfOwnedInstalledIdentityResultV2<*>): SelfOwnedInstalledIdentityRefusalV2 =
        assertIs<SelfOwnedInstalledIdentityResultV2.Refused>(result).code

    @Test
    fun theLedgerSetAndDomainsAreClosedAndDisjointFromAndroidV1() {
        assertEquals(listOf("EMPLOYEE_ENROLLMENTS", "EMPLOYEE_POLICY_ACTIVATIONS", "DEVICE_PAIRINGS", "DEVICE_ADMISSIONS"),
            SelfOwnedCompanyLedgerKindV2.entries.map { it.name })
        val expected = mapOf(
            SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS to Triple("native-company-employee-enrollment-ledger/v2",
                "native-company-employee-enrollment-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_EMPLOYEE_ENROLLMENTS_V2"),
            SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS to Triple("native-company-employee-policy-activation-ledger/v2",
                "native-company-employee-policy-activation-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_EMPLOYEE_POLICY_ACTIVATIONS_V2"),
            SelfOwnedCompanyLedgerKindV2.DEVICE_PAIRINGS to Triple("native-company-device-pairing-ledger/v2",
                "native-company-device-pairing-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_DEVICE_PAIRINGS_V2"),
            SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS to Triple("native-company-device-admission-ledger/v2",
                "native-company-device-admission-ledger-aad/v2", "ORIGINAL_NATIVE_COMPANY_DEVICE_ADMISSIONS_V2"),
        )
        expected.forEach { (kind, value) ->
            assertEquals(value, Triple(kind.ledgerDomain, kind.aadDomain, kind.purpose))
        }
    }

    @Test
    fun ledgerAadEmbedsTheCompleteCompanyRegistryAadOfTheSevenKeyBootstrap() {
        val company = F.macCompanyBootstrap()
        val registry = SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company)
        SelfOwnedCompanyLedgerKindV2.entries.forEach { kind ->
            assertContentEquals(header(kind.aadDomain).string(kind.purpose).field(registry).bytes(),
                SelfOwnedNativeCompanyLedgerWireV2.aad(kind, company))
        }
        val all = SelfOwnedCompanyLedgerKindV2.entries.map { SelfOwnedNativeCompanyLedgerWireV2.aad(it, company).toList() }
        assertEquals(all.size, all.toSet().size)
        assertFalse(SelfOwnedNativeCompanyLedgerWireV2.aad(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS, company)
            .contentEquals(NativeEmployeeEnrollmentWireV1.aad(A.companyBootstrap())))
    }

    @Test
    fun ledgerFrameWrapsExactlyTheSharedRecordBytesAndRefusesAnotherKind() {
        val records = DocumentedLayout().string("synthetic-shared-record-grammar").int(3).bytes()
        val kind = SelfOwnedCompanyLedgerKindV2.DEVICE_PAIRINGS
        val expected = header(kind.ledgerDomain).field(records).bytes()
        assertContentEquals(expected, SelfOwnedNativeCompanyLedgerWireV2.encodeLedger(kind, records))
        assertContentEquals(records, data(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, expected)))
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS, expected)))
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, expected + byteArrayOf(0))))
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, expected.copyOf(expected.size - 1))))
        val androidProfile = DocumentedLayout().string("ukkera.installation/self-owned-local-p256/v1")
            .string(kind.ledgerDomain).int(2).field(records).bytes()
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, androidProfile)))
        val v1Version = DocumentedLayout().string(profile).string(kind.ledgerDomain).int(1).field(records).bytes()
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, v1Version)))
    }

    @Test
    fun ledgerEnvelopeIsItsOwnClosedDomainAndRefusesBadShapes() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val ciphertext = ByteArray(40) { (it + 7).toByte() }
        val signature = ByteArray(64) { (it + 3).toByte() }
        val expected = header("native-company-ledger-envelope/v2").field(nonce).field(ciphertext).field(signature).bytes()
        assertContentEquals(expected, SelfOwnedNativeCompanyLedgerWireV2.encodeEnvelope(
            SelfOwnedRegistryEnvelopeV2(nonce.copyOf(), ciphertext.copyOf(), signature.copyOf())))
        val decoded = data(SelfOwnedNativeCompanyLedgerWireV2.decodeEnvelope(expected))
        assertContentEquals(nonce, decoded.nonce); assertContentEquals(ciphertext, decoded.ciphertext)
        assertContentEquals(signature, decoded.signature)
        val shortSignature = header("native-company-ledger-envelope/v2").field(nonce).field(ciphertext).field(ByteArray(63)).bytes()
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeEnvelope(shortSignature)))
        val companyRegistryEnvelope = header("native-provisioner-registry-envelope/v2")
            .field(nonce).field(ciphertext).field(signature).bytes()
        assertEquals(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN,
            refused(SelfOwnedNativeCompanyLedgerWireV2.decodeEnvelope(companyRegistryEnvelope)))
    }

    @Test
    fun theRootSignsOnlySharedEndorsementMessages() {
        val v1 = "ukkera.installation/self-owned-local-p256/v1"
        listOf("company-root-full-employee-approval/v1", "company-root-complete-employee-policy-approval/v1",
            "original-root-head-approved-one-device-pairing/v1", "original-root-head-approved-one-device-pairing/v2",
            "original-root-explicit-known-unsigned-device-pairing-disposition/v1",
            "original-root-explicit-known-unsigned-device-pairing-disposition/v2").forEach { domain ->
            SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage(
                DocumentedLayout().string(v1).string(domain).string("synthetic").bytes())
        }
        listOf(
            DocumentedLayout().string(v1).string("root-announcement/v1").bytes(),
            DocumentedLayout().string(v1).string("native-local-employee-authority-certificate/v1").bytes(),
            DocumentedLayout().string(profile).string("company-root-full-employee-approval/v1").bytes(),
            DocumentedLayout().string(v1).bytes(),
            ByteArray(0), byteArrayOf(0, 0, 0, 9, 1),
        ).forEach { message ->
            val failure = assertFailsWith<SelfOwnedFailureV1> {
                SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage(message)
            }
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, failure.reason)
        }
    }
}
