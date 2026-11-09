package com.ukkera.pos.app.independent.credentialentry

/** Literal fictional fixtures from credential-entry/cases.md V1 and additive V1-C1. */
internal data class FrozenCredentialRow(
    val id: String,
    val account: String,
    val password: String,
    val supportedName: String,
    val accountUnits: Int,
    val passwordUnits: Int,
    val nameUnits: Int,
    val eligible: Boolean,
)

internal data class FrozenNameRow(
    val id: String,
    val supportsName: Boolean,
    val name: String,
    val units: Int,
    val eligible: Boolean,
)

internal data class FrozenRuntimeInput(
    val id: String,
    val account: String,
    val password: String,
    val name: String,
)

internal data class FrozenCorrection(
    val rejected: FrozenRuntimeInput,
    val corrected: FrozenRuntimeInput,
)

internal enum class CredentialEntryOperation { SIGN_IN, ENROLLMENT }

private fun a(count: Int) = "a".repeat(count)
private fun p(count: Int) = "p".repeat(count)
private fun n(count: Int) = "n".repeat(count)
private fun r(count: Int) = "\uD83D\uDE80".repeat(count)

internal val frozenAccountPasswordRows = listOf(
    FrozenCredentialRow("B01", "", p(1), n(1), 0, 1, 1, false),
    FrozenCredentialRow("B02", " \t\r\n", p(1), n(1), 4, 1, 1, false),
    FrozenCredentialRow("B03", a(1), p(1), n(1), 1, 1, 1, true),
    FrozenCredentialRow("B04", a(127), p(255), n(1), 127, 255, 1, true),
    FrozenCredentialRow("B05", a(128), p(256), n(1), 128, 256, 1, true),
    FrozenCredentialRow("B06", a(129), p(1), n(1), 129, 1, 1, false),
    FrozenCredentialRow("B07", a(1), "", n(1), 1, 0, 1, false),
    FrozenCredentialRow("B08", a(1), p(257), n(1), 1, 257, 1, false),
    FrozenCredentialRow("B09", " cedar.fx ", " ", n(1), 10, 1, 1, true),
)

internal val frozenNameRows = listOf(
    FrozenNameRow("D01", true, "", 0, false),
    FrozenNameRow("D02", true, " \t\r\n", 4, false),
    FrozenNameRow("D03", true, n(1), 1, true),
    FrozenNameRow("D04", true, n(119), 119, true),
    FrozenNameRow("D05", true, n(120), 120, true),
    FrozenNameRow("D06", true, n(121), 121, false),
    FrozenNameRow("D07", false, "", 0, true),
    FrozenNameRow("D08", false, n(1), 1, false),
    FrozenNameRow("D09", false, " ", 1, false),
)

internal val frozenUnicodeAccountPasswordRows = listOf(
    FrozenCredentialRow("U01", r(64), p(1), n(1), 128, 1, 1, true),
    FrozenCredentialRow("U02", r(64) + "a", p(1), n(1), 129, 1, 1, false),
    FrozenCredentialRow("U03", a(1), r(128), n(1), 1, 256, 1, true),
    FrozenCredentialRow("U04", a(1), r(128) + "p", n(1), 1, 257, 1, false),
    FrozenCredentialRow("U07", "e\u0301", "e\u0301", "e\u0301", 2, 2, 2, true),
)

internal val frozenUnicodeSupportedNameRows = listOf(
    FrozenCredentialRow("U05", a(1), p(1), r(60), 1, 1, 120, true),
    FrozenCredentialRow("U06", a(1), p(1), r(60) + "n", 1, 1, 121, false),
)

internal val frozenForwardingRows = listOf(
    FrozenCredentialRow("V01", a(1), p(1), n(1), 1, 1, 1, true),
    FrozenCredentialRow("V02", a(128), p(256), n(120), 128, 256, 120, true),
    FrozenCredentialRow("V03", " cedar.fx ", " ", " Cedar ", 10, 1, 7, true),
    FrozenCredentialRow("V04", r(64), r(128), r(60), 128, 256, 120, true),
    FrozenCredentialRow("V05", "e\u0301", "e\u0301", "e\u0301", 2, 2, 2, true),
)

internal const val FROZEN_FAILURE_PASSWORD = "cedar-test-secret-73!"

internal fun frozenFailureInput(operation: CredentialEntryOperation, supportsName: Boolean) = FrozenRuntimeInput(
    "CEN-V1-006",
    "cedar.failure.fx",
    FROZEN_FAILURE_PASSWORD,
    if (operation == CredentialEntryOperation.ENROLLMENT && supportsName) "Cedar Test" else "",
)

internal fun FrozenCredentialRow.runtimeInput(
    caseId: String,
    operation: CredentialEntryOperation,
    supportsName: Boolean,
) = FrozenRuntimeInput("$caseId/$id", account, password,
    if (operation == CredentialEntryOperation.ENROLLMENT && supportsName) supportedName else "")

internal fun frozenRejectedInputs(operation: CredentialEntryOperation, supportsName: Boolean): List<FrozenRuntimeInput> {
    // These verdicts are the frozen table literals; no production predicate chooses the oracle.
    val accountPassword = frozenAccountPasswordRows.filter { !it.eligible }.map {
        it.runtimeInput("CEN-V1-001", operation, supportsName)
    } + frozenUnicodeAccountPasswordRows.filter { !it.eligible }.map {
        it.runtimeInput("CEN-V1-003", operation, supportsName)
    }
    if (operation == CredentialEntryOperation.SIGN_IN) return accountPassword
    return accountPassword + frozenNameRows.filter { it.supportsName == supportsName && !it.eligible }.map {
        FrozenRuntimeInput("CEN-V1-002/${it.id}", a(1), p(1), it.name)
    } + if (supportsName) frozenUnicodeSupportedNameRows.filter { !it.eligible }.map {
        it.runtimeInput("CEN-V1-003", operation, supportsName)
    } else emptyList()
}

internal fun frozenOversizeCorrections(operation: CredentialEntryOperation, supportsName: Boolean): List<FrozenCorrection> {
    val name = if (operation == CredentialEntryOperation.ENROLLMENT && supportsName) n(1) else ""
    fun correction(id: String, invalidAccount: String, validAccount: String,
        invalidPassword: String, validPassword: String, invalidName: String = name, validName: String = name) =
        FrozenCorrection(
            FrozenRuntimeInput("CEN-V1-004/$id/oversized", invalidAccount, invalidPassword, invalidName),
            FrozenRuntimeInput("CEN-V1-004/$id/corrected", validAccount, validPassword, validName),
        )
    val accountPassword = listOf(
        correction("B06", a(129), a(128), p(1), p(1)),
        correction("B08", a(1), a(1), p(257), p(256)),
        correction("U02", r(64) + "a", r(64), p(1), p(1)),
        correction("U04", a(1), a(1), r(128) + "p", r(128)),
    )
    return accountPassword + if (operation == CredentialEntryOperation.ENROLLMENT && supportsName) listOf(
        correction("D06", a(1), a(1), p(1), p(1), n(121), n(120)),
        correction("U06", a(1), a(1), p(1), p(1), r(60) + "n", r(60)),
    ) else emptyList()
}
