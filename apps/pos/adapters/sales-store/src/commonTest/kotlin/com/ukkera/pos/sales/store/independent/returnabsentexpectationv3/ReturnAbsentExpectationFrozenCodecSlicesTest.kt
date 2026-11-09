package com.ukkera.pos.sales.store.independent.returnabsentexpectationv3

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Frozen RAEX3-001/002 codec slices only. No stored historical command, old Master,
 * protected transaction, direct RPC refusal or unchanged business-state proof is bound.
 */
class ReturnAbsentExpectationFrozenCodecSlicesTest {
    private val input = ReturnAbsentExpectationFrozenInputs
    private val caseId = DomainId(input.caseId)

    @Test
    fun raex3001_omitted_exactBytesAndNullExpectation() {
        assertCanonical(input.omitted, LocalReturnCaseRequestV1.Qualify(caseId, Revision(11)), input.omittedOperationId)
        assertContentEquals(input.omitted.encodeToByteArray(), LocalReturnCaseCommandCodecV1.encode(
            LocalReturnCaseRequestV1.Qualify(caseId, Revision(11), null)).copyBytes())
    }

    @Test
    fun raex3001_legacy7_exactBytesAndSuppliedExpectation() {
        assertCanonical(input.legacy7, LocalReturnCaseRequestV1.Qualify(caseId, Revision(11), Revision(7)), input.legacyOperationId)
    }

    @Test
    fun raex3001_legacyZero_exactBytesAndNonNullZero() {
        assertCanonical(input.legacyZero, LocalReturnCaseRequestV1.Qualify(caseId, Revision(11), Revision(0)), input.legacyOperationId)
    }

    @Test
    fun raex3001_maximum_exactSigned64BitValuesAndBytes() {
        val maximum = Revision(input.maximumRevision)
        assertCanonical(input.maximum, LocalReturnCaseRequestV1.Qualify(caseId, maximum, maximum), input.legacyOperationId)
    }

    @Test fun raex3002_explicit_null_decodeAndNormalizeRefuse() = assertInvalid("explicit_null")
    @Test fun raex3002_unknown_field_decodeAndNormalizeRefuse() = assertInvalid("unknown_field")
    @Test fun raex3002_reordered_fields_decodeAndNormalizeRefuse() = assertInvalid("reordered_fields")
    @Test fun raex3002_noncanonical_spacing_decodeAndNormalizeRefuse() = assertInvalid("noncanonical_spacing")
    @Test fun raex3002_uppercase_uuid_decodeAndNormalizeRefuse() = assertInvalid("uppercase_uuid")
    @Test fun raex3002_missing_case_revision_decodeAndNormalizeRefuse() = assertInvalid("missing_case_revision")
    @Test fun raex3002_duplicate_case_revision_decodeAndNormalizeRefuse() = assertInvalid("duplicate_case_revision")
    @Test fun raex3002_numeric_case_with_string_entitlement_decodeAndNormalizeRefuse() = assertInvalid("numeric_case_with_string_entitlement")
    @Test fun raex3002_numeric_entitlement_with_string_case_decodeAndNormalizeRefuse() = assertInvalid("numeric_entitlement_with_string_case")
    @Test fun raex3002_boolean_case_revision_decodeAndNormalizeRefuse() = assertInvalid("boolean_case_revision")
    @Test fun raex3002_boolean_entitlement_revision_decodeAndNormalizeRefuse() = assertInvalid("boolean_entitlement_revision")
    @Test fun raex3002_null_case_revision_decodeAndNormalizeRefuse() = assertInvalid("null_case_revision")
    @Test fun raex3002_preserved_v2_numeric_omitted_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_omitted")
    @Test fun raex3002_preserved_v2_numeric_legacy_7_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_legacy_7")
    @Test fun raex3002_preserved_v2_numeric_legacy_zero_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_legacy_zero")
    @Test fun raex3002_preserved_v2_numeric_maximum_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_maximum")
    @Test fun raex3002_preserved_v2_numeric_present_omitted_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_present_omitted")
    @Test fun raex3002_preserved_v2_numeric_present_stale_6_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_present_stale_6")
    @Test fun raex3002_preserved_v2_numeric_present_correct_7_decodeAndNormalizeRefuse() = assertInvalid("preserved_v2_numeric_present_correct_7")
    @Test fun raex3002_negative_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("negative_expectedCaseRevision")
    @Test fun raex3002_negative_zero_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("negative_zero_expectedCaseRevision")
    @Test fun raex3002_leading_zero_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("leading_zero_expectedCaseRevision")
    @Test fun raex3002_double_zero_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("double_zero_expectedCaseRevision")
    @Test fun raex3002_plus_sign_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("plus_sign_expectedCaseRevision")
    @Test fun raex3002_fractional_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("fractional_expectedCaseRevision")
    @Test fun raex3002_exponent_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("exponent_expectedCaseRevision")
    @Test fun raex3002_empty_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("empty_expectedCaseRevision")
    @Test fun raex3002_overflow_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("overflow_expectedCaseRevision")
    @Test fun raex3002_leading_whitespace_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("leading_whitespace_expectedCaseRevision")
    @Test fun raex3002_trailing_whitespace_expectedCaseRevision_decodeAndNormalizeRefuse() = assertInvalid("trailing_whitespace_expectedCaseRevision")
    @Test fun raex3002_negative_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("negative_expectedEntitlementRevision")
    @Test fun raex3002_negative_zero_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("negative_zero_expectedEntitlementRevision")
    @Test fun raex3002_leading_zero_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("leading_zero_expectedEntitlementRevision")
    @Test fun raex3002_double_zero_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("double_zero_expectedEntitlementRevision")
    @Test fun raex3002_plus_sign_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("plus_sign_expectedEntitlementRevision")
    @Test fun raex3002_fractional_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("fractional_expectedEntitlementRevision")
    @Test fun raex3002_exponent_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("exponent_expectedEntitlementRevision")
    @Test fun raex3002_empty_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("empty_expectedEntitlementRevision")
    @Test fun raex3002_overflow_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("overflow_expectedEntitlementRevision")
    @Test fun raex3002_leading_whitespace_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("leading_whitespace_expectedEntitlementRevision")
    @Test fun raex3002_trailing_whitespace_expectedEntitlementRevision_decodeAndNormalizeRefuse() = assertInvalid("trailing_whitespace_expectedEntitlementRevision")

    private fun assertCanonical(body: String, expected: LocalReturnCaseRequestV1.Qualify, operation: String) {
        val bytes = StoredBytes(body.encodeToByteArray())
        val decoded = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(bytes))
        assertEquals(expected, decoded)
        assertContentEquals(bytes.copyBytes(), LocalReturnCaseCommandCodecV1.encode(expected).copyBytes())
        assertContentEquals(bytes.copyBytes(), LocalReturnCaseCommandCodecV1.encode(decoded).copyBytes())
        assertContentEquals(bytes.copyBytes(), LocalReturnCaseCommandCodecV1.normalize(bytes).copyBytes())
        val operationId = OperationId(operation)
        val command = LocalReturnCaseCommandCodecV1.command(operationId, expected)
        assertEquals(operationId, command.operationId)
        assertEquals(CommandKey("sales.return.case.write", 1), command.command)
        assertContentEquals(bytes.copyBytes(), command.payload.copyBytes())
        assertNull(command.actionApprovalId)
    }

    private fun assertInvalid(name: String) {
        val body = StoredBytes(input.invalid.getValue(name).encodeToByteArray())
        assertFails("Frozen $name must not decode") { LocalReturnCaseCommandCodecV1.decode(body) }
        assertFails("Frozen $name must not normalize into an executable body") { LocalReturnCaseCommandCodecV1.normalize(body) }
    }
}
