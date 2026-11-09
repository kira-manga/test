package com.ukkera.pos.sales.store.independent.returnabsentexpectationv3

/** Literal decimal-string wire inputs from frozen return-absent-expectation-v3.0 fixtures.json. */
internal object ReturnAbsentExpectationFrozenInputs {
    const val caseId = "c1530000-0000-4000-8000-000000000001"
    const val omittedOperationId = "c1531000-0000-4000-8000-000000000001"
    const val legacyOperationId = "c1531000-0000-4000-8000-000000000002"
    const val maximumRevision = 9_223_372_036_854_775_807L

    const val omitted = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11"}"""
    const val legacy7 = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"7"}"""
    const val legacyZero = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"0"}"""
    const val maximum = """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"9223372036854775807","expectedEntitlementRevision":"9223372036854775807"}"""

    val invalid: Map<String, String> = linkedMapOf(
        "explicit_null" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":null}""",
        "unknown_field" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","unknown":true}""",
        "reordered_fields" to """{"caseId":"c1530000-0000-4000-8000-000000000001","type":"QUALIFY","expectedCaseRevision":"11"}""",
        "noncanonical_spacing" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision": "11"}""",
        "uppercase_uuid" to """{"type":"QUALIFY","caseId":"C1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11"}""",
        "missing_case_revision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001"}""",
        "duplicate_case_revision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedCaseRevision":"11"}""",
        "numeric_case_with_string_entitlement" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":11,"expectedEntitlementRevision":"7"}""",
        "numeric_entitlement_with_string_case" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":7}""",
        "boolean_case_revision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":true}""",
        "boolean_entitlement_revision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":false}""",
        "null_case_revision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":null}""",
        "preserved_v2_numeric_omitted" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":11}""",
        "preserved_v2_numeric_legacy_7" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":11,"expectedEntitlementRevision":7}""",
        "preserved_v2_numeric_legacy_zero" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":11,"expectedEntitlementRevision":0}""",
        "preserved_v2_numeric_maximum" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":9223372036854775807,"expectedEntitlementRevision":9223372036854775807}""",
        "preserved_v2_numeric_present_omitted" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000002","expectedCaseRevision":11}""",
        "preserved_v2_numeric_present_stale_6" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000002","expectedCaseRevision":11,"expectedEntitlementRevision":6}""",
        "preserved_v2_numeric_present_correct_7" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000002","expectedCaseRevision":11,"expectedEntitlementRevision":7}""",
        "negative_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"-1"}""",
        "negative_zero_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"-0"}""",
        "leading_zero_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"011"}""",
        "double_zero_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"00"}""",
        "plus_sign_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"+11"}""",
        "fractional_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11.0"}""",
        "exponent_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11e0"}""",
        "empty_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":""}""",
        "overflow_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"9223372036854775808"}""",
        "leading_whitespace_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":" 11"}""",
        "trailing_whitespace_expectedCaseRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11 "}""",
        "negative_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"-1"}""",
        "negative_zero_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"-0"}""",
        "leading_zero_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"07"}""",
        "double_zero_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"00"}""",
        "plus_sign_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"+7"}""",
        "fractional_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"7.0"}""",
        "exponent_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"7e0"}""",
        "empty_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":""}""",
        "overflow_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"9223372036854775808"}""",
        "leading_whitespace_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":" 7"}""",
        "trailing_whitespace_expectedEntitlementRevision" to """{"type":"QUALIFY","caseId":"c1530000-0000-4000-8000-000000000001","expectedCaseRevision":"11","expectedEntitlementRevision":"7 "}""",
    )
}
