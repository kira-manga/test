package com.ukkera.pos.security.independent.androidapi24v1

import com.ukkera.pos.security.AndroidNativeKeyPolicy
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.ProtectedLocalTable
import com.ukkera.pos.security.ProtectedStorageKeyReference
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Direct public-contract slices of unchanged AC24-C01/C02/C03/C06/C07 v1.
 * Literal expectations below transcribe fixtures-v1.json, not native observations.
 * E-H-UNKNOWN/E-CORE-UNAVAILABLE and malformed/context/acquisition variants have no
 * binding in this enum policy. C04/C05, generation/fallback counts, authenticated
 * record opening, SQLite, native hardware and actual OS upgrades are NOT tested.
 * No whole frozen case or POS acceptance gate is satisfied by these slices.
 */
class AndroidApi24V1CommonSlicesTest {
    private data class Observation(val id: String, val evidence: AndroidNativeKeySecurity,
        val allowed: Set<SelfOwnedKeyProtectionV1>)

    private val os = SelfOwnedKeyProtectionV1.OS_ISOLATED
    private val hardware = SelfOwnedKeyProtectionV1.SECURE_HARDWARE
    private val strongbox = SelfOwnedKeyProtectionV1.STRONGBOX
    private val observations = listOf(
        Observation("E-SW", AndroidNativeKeySecurity.SOFTWARE, setOf(os)),
        Observation("E-HU", AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED, setOf(os, hardware)),
        Observation("E-TEE", AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT, setOf(os, hardware)),
        Observation("E-SB", AndroidNativeKeySecurity.STRONGBOX, setOf(os, hardware, strongbox)),
    )

    @Test
    fun c01_representedEvidenceDecisionsDoNotDependOnRuntimeLabels() {
        for (runtime in listOf("R24", "R28", "R31")) {
            for (observation in observations) for (policy in listOf(os, hardware, strongbox)) {
                assertEquals(policy in observation.allowed,
                    AndroidNativeKeyPolicy.permits(policy, observation.evidence),
                    "AC24-C01-${observation.id}-${policy.name}-$runtime")
            }
        }
        // permits is evidence policy only. This does not assert that any old OS can
        // acquire an E-SB key or that its native StrongBox facility is supported.
    }

    @Test
    fun c02_c03_unclassifiedOrNonStrongBoxEvidenceCannotSubstituteForStrongBox() {
        val generic = AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
        assertEquals(true, AndroidNativeKeyPolicy.permits(os, generic), "AC24-C02 optional exact kind")
        assertEquals(true, AndroidNativeKeyPolicy.permits(hardware, generic), "AC24-C02 proven generic hardware")
        for (evidence in listOf(generic, AndroidNativeKeySecurity.SOFTWARE,
            AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT)) {
            assertFalse(AndroidNativeKeyPolicy.permits(strongbox, evidence), "AC24-C03 unproved StrongBox")
        }
        assertEquals(generic, AndroidNativeKeyPolicy.originalWitness(generic, generic),
            "AC24-C02 unclassified evidence is not normalized into an exact kind")
    }

    @Test
    fun c06_richerCurrentEvidenceDoesNotRewriteOriginalUnclassifiedWitness() {
        val original = AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
        for (current in listOf(original, AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT,
            AndroidNativeKeySecurity.STRONGBOX)) {
            assertEquals(true, AndroidNativeKeyPolicy.permits(hardware, current), "AC24-C06 P-HW")
            assertEquals(original, AndroidNativeKeyPolicy.originalWitness(current, original),
                "AC24-C06 original E-HU stays original E-HU")
        }
        assertFalse(AndroidNativeKeyPolicy.permits(hardware, AndroidNativeKeySecurity.SOFTWARE), "AC24-C06 E-SW P-HW")
        assertEquals(true, AndroidNativeKeyPolicy.permits(os, AndroidNativeKeySecurity.SOFTWARE), "AC24-C06 explicit P-OS")
        assertEquals(AndroidNativeKeySecurity.SOFTWARE,
            AndroidNativeKeyPolicy.originalWitness(AndroidNativeKeySecurity.SOFTWARE, original),
            "AC24-C06 a weaker current observation is not projected into original hardware proof")
        for (observation in observations) {
            assertEquals(observation.evidence == AndroidNativeKeySecurity.STRONGBOX,
                AndroidNativeKeyPolicy.permits(strongbox, observation.evidence), "AC24-C06 explicit P-SB")
        }
    }

    @Test
    fun c07_explicitWireProfilesEnvelopeAndPublicLengthBoundsRemainFrozen() {
        val keys = profileKeys(scope)
        assertEquals(budgets.keys, ProtectedLocalTable.entries.map { it.wireName }.toSet(), "AC24-C07 ten wire identities")
        for (listing in listOf(ProtectedLocalTable.entries.toList(), ProtectedLocalTable.entries.reversed())) {
            for ((wire, maximum) in budgets) {
                val table = listing.single { it.wireName == wire }
                assertEquals(maximum, table.maximumPlaintextBytes, "AC24-C07 $wire budget")
                assertEquals(1, table.schemaVersion, "AC24-C07 $wire schema")
                val binding = ProtectedLocalBinding(keys.getValue(wire), Revision(0))
                binding.requirePlaintextSize(1)
                binding.requirePlaintextSize(maximum)
                assertFailsWith<IllegalArgumentException>("AC24-C07 $wire empty") { binding.requirePlaintextSize(0) }
                assertFailsWith<IllegalArgumentException>("AC24-C07 $wire excess") { binding.requirePlaintextSize(maximum + 1) }
            }
        }
        assertEquals(1, ProtectedLocalStorageFormat.FORMAT_VERSION)
        assertEquals("AES-256-GCM", ProtectedLocalStorageFormat.ALGORITHM)
        assertEquals(12, ProtectedLocalStorageFormat.NONCE_BYTES)
        assertEquals(16, ProtectedLocalStorageFormat.TAG_BYTES)
        // Length guards only: no arbitrary byte array is called a schema-valid record.
    }

    @Test
    fun c07_publicAadDistinguishesBoundCoordinatesWithoutClaimingDecryption() {
        val reference = ProtectedStorageKeyReference(id("005"))
        val changedReference = ProtectedStorageKeyReference(id("007"))
        val keys = profileKeys(scope)
        val original = keys.mapValues { ProtectedLocalBinding(it.value, Revision(0)).aad(reference) }
        try {
            for ((wire, key) in keys) {
                val aad = original.getValue(wire)
                for ((otherWire, other) in original) if (wire != otherWire) {
                    assertFalse(aad.contentEquals(other), "AC24-C07 explicit table identity")
                }
                distinct(aad, ProtectedLocalBinding(key, Revision(0)), changedReference, "storage key reference")
                for (changedScope in listOf(scope.copy(organizationId = id("004")),
                    scope.copy(companyId = id("004")), scope.copy(branchId = id("004")))) {
                    distinct(aad, ProtectedLocalBinding(profileKeys(changedScope).getValue(wire), Revision(0)),
                        reference, "organization/company/branch")
                }
                distinct(aad, ProtectedLocalBinding(profileKeys(scope, id("009")).getValue(wire), Revision(0)),
                    reference, "record identity")
                if (key is ProtectedLocalRecordKey.Account || key is ProtectedLocalRecordKey.EffectivePolicy ||
                    key is ProtectedLocalRecordKey.Admission || key is ProtectedLocalRecordKey.Throttle ||
                    key is ProtectedLocalRecordKey.AuthConfiguration || key is ProtectedLocalRecordKey.ActionApproval) {
                    distinct(aad, ProtectedLocalBinding(key, Revision(1)), reference, "record revision")
                }
            }
            val account = keys.getValue("throttle") as ProtectedLocalRecordKey.Throttle
            val device = account.copy(key = account.key.copy(dimension = ThrottleDimension.Device(id("006"))))
            assertEquals("account", account.dimensionKey)
            assertEquals("device:a2400000-0000-4000-8000-000000000006", device.dimensionKey)
            distinct(original.getValue("throttle"), ProtectedLocalBinding(device, Revision(0)), reference, "throttle dimension")
        } finally { original.values.forEach { it.fill(0) } }
        // Fixed domain/schema/algorithm constants and unknown-envelope rejection need
        // real envelope/codec bindings; AAD distinction does not prove AEAD rejection.
    }

    private fun distinct(original: ByteArray, binding: ProtectedLocalBinding,
        reference: ProtectedStorageKeyReference, field: String) {
        val changed = binding.aad(reference)
        try { assertFalse(original.contentEquals(changed), "AC24-C07 $field") }
        finally { changed.fill(0) }
    }

    private fun profileKeys(branch: BranchScope, record: DomainId = id("008")): Map<String, ProtectedLocalRecordKey> = linkedMapOf(
        "account" to ProtectedLocalRecordKey.Account(branch, record),
        "effective_policy" to ProtectedLocalRecordKey.EffectivePolicy(branch, record),
        "admission" to ProtectedLocalRecordKey.Admission(branch, record),
        "throttle" to ProtectedLocalRecordKey.Throttle(ThrottleKey(branch, record, ThrottleDimension.Account)),
        "local_session_issuance" to ProtectedLocalRecordKey.SessionIssuance(branch, record),
        "local_auth_configuration" to ProtectedLocalRecordKey.AuthConfiguration(branch, record),
        "local_refresh_issuance" to ProtectedLocalRecordKey.RefreshIssuance(branch, record),
        "local_auth_event" to ProtectedLocalRecordKey.AuthEvent(branch, record),
        "local_session_archive" to ProtectedLocalRecordKey.SessionArchive(branch, record),
        "local_action_approval" to ProtectedLocalRecordKey.ActionApproval(branch, record),
    )

    private fun id(suffix: String): DomainId = DomainId("a2400000-0000-4000-8000-000000000$suffix")
    private val scope = BranchScope(id("001"), id("002"), id("003"))
    private val budgets = linkedMapOf("account" to 262144, "effective_policy" to 1048576,
        "admission" to 16384, "throttle" to 4096, "local_session_issuance" to 4096,
        "local_auth_configuration" to 4096, "local_refresh_issuance" to 4096,
        "local_auth_event" to 4096, "local_session_archive" to 4096, "local_action_approval" to 16384)
}
