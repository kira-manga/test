package com.ukkera.pos.rpc.independent.authenticationcurrentrenewalv2

import com.ukkera.pos.rpc.isAuthenticationCurrentReadReclaimable
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Frozen eligibility assertions against the actual production predicate: 126 fixture rows.
 * Controlled observations are inputs, not witnesses of native acceptance or clean settlement.
 * No slot is allocated/released here. Quota, exact scope callbacks, original binding, completed
 * revocation, private publication and all RPC/STORE/APP cases remain outside this supporting slice.
 */
class AuthenticationCurrentRenewalV2RetentionPolicySupportingTest {
    @Test
    fun acr2S003AllNinetySixFiveConditionRowsUseTheActualRetentionPredicate() {
        AuthenticationCurrentRenewalV2Frozen.rows("five_condition_retention").forEach { row ->
            assertFrozenRetention(row, startedReadEligibility(row.inputs.acr2Object("conditions")))
        }
    }

    @Test
    fun acr2S004AllEightNeverStartedNoNativeRowsUseTheActualRetentionPredicate() {
        AuthenticationCurrentRenewalV2Frozen.rows("never_started_without_native_work").forEach { row ->
            val conditions = row.inputs.acr2Object("conditions")
            assertFrozenRetention(row, isAuthenticationCurrentReadReclaimable(
                expired = conditions.acr2Boolean("expired"),
                scopeInactive = conditions.acr2Boolean("exact_scope_revocation_complete"),
                privateRetentionCleared = conditions.acr2Boolean("all_owned_decoded_request_buffers_cleared_without_live_claimants"),
                started = row.inputs.acr2Boolean("handler_started"),
                handlerFinished = false,
                nativeClean = false,
                authorityChecks = row.inputs.acr2Int("accepted_scope_native_authority_work"),
                authorityUncertain = false,
            ))
        }
        // Eligibility does not qualify the final row's late-old-scope start refusal.
    }

    @Test
    fun acr2S005NineUnresolvedDeliveryAndCleanupRowsPreserveFrozenEligibility() {
        AuthenticationCurrentRenewalV2Frozen.rows("hold_and_cleanup").forEach { row ->
            assertFrozenRetention(row, startedReadEligibility(row.inputs.acr2Object("conditions")))
        }
        // Neither overload/reuse refusal nor completed reclamation is represented by a boolean.
    }

    @Test
    fun acr2S008FiveCancellationAndBufferRowsPreserveFrozenEligibility() {
        AuthenticationCurrentRenewalV2Frozen.rows("buffer_and_cancellation_schedules").forEach { row ->
            val input = row.inputs
            assertFrozenRetention(row, isAuthenticationCurrentReadReclaimable(
                expired = input.acr2Boolean("expired"),
                scopeInactive = input.acr2Boolean("exact_scope_revocation_complete"),
                privateRetentionCleared = input.acr2Boolean("owned_cleanup_complete"),
                started = input.acr2Int("accepted_current_handler_work") != 0,
                handlerFinished = input.acr2Boolean("handler_finished_or_never_started_with_terminal_request"),
                nativeClean = input.acr2Boolean("all_accepted_native_work_known_clean_settled_or_confirmed_absent"),
                authorityChecks = input.acr2Int("accepted_scope_native_authority_checks"),
                authorityUncertain = false,
            ))
        }
        // Actual accepted-work cancellation, private-view cleanup and late output remain unwired.
    }

    @Test
    fun acr2S010EightPreHandlerAuthorityAndOwnedInputRowsPreserveFrozenEligibility() {
        AuthenticationCurrentRenewalV2Frozen.rows("pre_handler_native_and_decoded_input_overlaps").forEach { row ->
            val input = row.inputs
            val allNativeClean = input.acr2Boolean("all_accepted_native_work_known_clean_settled_or_confirmed_absent")
            // Accepted checks are not CURRENT dispatches or outstanding-check counts. Only the
            // frozen all-clean observation clears outstanding scope work; wrapper finish does not.
            val outstandingChecks = if (allNativeClean) 0 else input.acr2Int("accepted_scope_native_authority_checks")
            val currentKnownClean = allNativeClean || row.id == "ACR2-S010-HANDLER-FINISHED-CHECK-RUNNING"
            assertFrozenRetention(row, isAuthenticationCurrentReadReclaimable(
                expired = input.acr2Boolean("expired"),
                scopeInactive = input.acr2Boolean("exact_scope_revocation_complete"),
                privateRetentionCleared = input.acr2Boolean("all_owned_decoded_inputs_and_private_views_cleared"),
                started = input.acr2Boolean("handler_started"),
                handlerFinished = input.acr2Boolean("handler_finished_or_confirmed_never_started_terminal"),
                nativeClean = currentKnownClean,
                authorityChecks = outstandingChecks,
                authorityUncertain = row.id == "ACR2-S010-PRE-HANDLER-CHECK-UNCERTAIN",
            ))
        }
        // These inputs do not establish accepted native work, SDK scope revocation or slot release.
    }
}

private fun startedReadEligibility(conditions: JsonObject): Boolean = isAuthenticationCurrentReadReclaimable(
    expired = conditions.acr2Boolean("expired"),
    scopeInactive = conditions.acr2Boolean("exact_scope_revocation_complete"),
    privateRetentionCleared = conditions.acr2Boolean("all_owned_private_buffers_cleared_without_live_claimants"),
    started = true,
    handlerFinished = conditions.acr2Boolean("handler_finished"),
    nativeClean = conditions.acr2Boolean("all_accepted_native_work_known_clean_settled"),
    authorityChecks = 0,
    authorityUncertain = false,
)

private fun assertFrozenRetention(row: AuthenticationCurrentRenewalV2Variant, actual: Boolean) {
    assertEquals(row.expected.acr2Boolean("reclamation_permitted"), actual, row.id)
    assertEquals(row.expected.acr2String("retention"), if (actual) "RECLAIMABLE" else "HELD", row.id)
}
