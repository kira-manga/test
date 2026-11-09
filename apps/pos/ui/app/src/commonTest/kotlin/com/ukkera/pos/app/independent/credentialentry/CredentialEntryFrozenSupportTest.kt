package com.ukkera.pos.app.independent.credentialentry

import com.ukkera.pos.app.localEnrollmentInputSupported
import com.ukkera.pos.app.localSignInInputSupported
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shared support only; eligibility does not establish dispatch, authentication or UI behavior. */
class CredentialEntryFrozenSupportTest {
    @Test fun cenV1001SignInAccountPasswordBoundaries() {
        assertSignInRows("CEN-V1-001", frozenAccountPasswordRows)
    }

    @Test fun cenV1001EnrollmentAccountPasswordBoundariesWithSupportedName() {
        assertEnrollmentRows("CEN-V1-001", frozenAccountPasswordRows, supportsName = true)
    }

    @Test fun cenV1001EnrollmentAccountPasswordBoundariesWithUnsupportedName() {
        assertEnrollmentRows("CEN-V1-001", frozenAccountPasswordRows, supportsName = false)
    }

    @Test fun cenV1002EnrollmentNameCapabilityAndBoundaries() {
        frozenNameRows.forEach { row ->
            val label = "CEN-V1-002/${row.id}"
            assertEquals(row.units, row.name.length, "$label: fixture UTF-16 units")
            assertEquals(row.eligible, localEnrollmentInputSupported("a", row.name, 1, row.supportsName),
                "$label: frozen eligibility")
        }
    }

    @Test fun cenV1003SignInUtf16Boundaries() {
        assertSignInRows("CEN-V1-003", frozenUnicodeAccountPasswordRows)
    }

    @Test fun cenV1003EnrollmentUtf16BoundariesWithSupportedName() {
        assertEnrollmentRows("CEN-V1-003", frozenUnicodeAccountPasswordRows + frozenUnicodeSupportedNameRows,
            supportsName = true)
    }

    @Test fun cenV1003EnrollmentUtf16BoundariesWithUnsupportedName() {
        assertEnrollmentRows("CEN-V1-003", frozenUnicodeAccountPasswordRows, supportsName = false)
    }

    private fun assertSignInRows(caseId: String, rows: List<FrozenCredentialRow>) {
        rows.forEach { row ->
            val label = "$caseId/${row.id}/sign-in"
            assertUnits(row, label)
            assertEquals(row.eligible, localSignInInputSupported(row.account, row.password.length),
                "$label: frozen eligibility")
        }
    }

    private fun assertEnrollmentRows(caseId: String, rows: List<FrozenCredentialRow>, supportsName: Boolean) {
        rows.forEach { row ->
            val label = "$caseId/${row.id}/enrollment/name-supported=$supportsName"
            assertUnits(row, label)
            val name = if (supportsName) row.supportedName else ""
            assertEquals(row.eligible, localEnrollmentInputSupported(row.account, name, row.password.length, supportsName),
                "$label: frozen eligibility")
        }
    }

    private fun assertUnits(row: FrozenCredentialRow, label: String) {
        assertEquals(row.accountUnits, row.account.length, "$label: account fixture UTF-16 units")
        assertEquals(row.passwordUnits, row.password.length, "$label: password fixture UTF-16 units")
        assertEquals(row.nameUnits, row.supportedName.length, "$label: name fixture UTF-16 units")
    }
}
