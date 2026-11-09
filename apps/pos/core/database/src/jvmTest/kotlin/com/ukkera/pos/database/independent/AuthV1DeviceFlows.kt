package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Frozen I003, released by the lead only after the actual public revokeDevice path existed. */
internal suspend fun authV1DeviceSlices(directory: Path, design: JsonObject, journal: AuthV1Journal) {
    authV1WithFixture(directory, design, journal, "device-revocation",
        AuthV1FixtureSetup(initialDeviceAdministrationForM = true)) { f ->
        val baseline = f.snapshot("before-device-revocation")
        val requestId = authV1EphemeralId()
        // Non-secret identity is durably recorded before submission; no external message/transport.
        journal.event("intent", "device-revocation-request", "RECORDED_BEFORE_SUBMISSION", buildJsonObject {
            put("request_id", requestId.value)
            put("device_id", f.device("A").value)
            put("expected_admission_revision", baseline.admissions.getValue("A").revision.value)
            put("operator_handle", "M")
        })
        var eventId: DomainId? = null
        journal.slice("authorized_device_revoke_auth_paths") {
            f.currentControl("A", "device-revoke-before")
            f.currentControl("B", "device-revoke-before")
            journal.event("diagnostic", "device-revocation-revoke-operation", "BEFORE_PUBLIC_REVOKE_DEVICE", buildJsonObject {
                put("diagnostic_only", true)
                put("request_id", requestId.value)
            })
            f.recordDiagnosticCut(AuthV1RetirementCut.BEFORE_REVOKE_DEVICE_CALL)
            val revocationResult = f.endpoint.revokeDevice(f.access("M"), requestId, f.device("A"),
                baseline.admissions.getValue("A").revision)
            f.recordDiagnosticCut(AuthV1RetirementCut.AFTER_REVOKE_DEVICE_RETURN)
            journal.event("diagnostic", "device-revocation-revoke-operation", "AFTER_PUBLIC_REVOKE_DEVICE", buildJsonObject {
                put("diagnostic_only", true)
                put("request_id", requestId.value)
                put("result", revocationResult.authV1Code())
            })
            eventId = applied("M_device_revocation", revocationResult)
            journal.control("device-revoke-does-not-close-synthetic-peer", f.syntheticPeerIsCurrent("A"), buildJsonObject {
                put("actual_public_revoke_device_called", true)
                put("synthetic_peer_lifetime_remains_current", true)
                put("physical_channel_qualification", false)
            })
            revokedCredentials(f, f.tokens("A"), "A", "device_revocation")
            authenticationRefused("correct_password_over_revoked_device", f.signIn("A"))
            val after = f.snapshot("after-device-revocation")
            expect("device_admission_is_actually_revoked", !after.admissions.getValue("A").admitted)
            expect("prior_A_device_session_retained_revoked", after.accounts.getValue("A").state.sessions.singleOrNull {
                it.sessionId == f.tokens("A").view.sessionId
            }?.status == SessionStatus.REVOKED)
            expect("A_account_stays_enabled_for_other_admitted_devices", after.accounts.getValue("A").state.enabled)
            authEvent(after, checkNotNull(eventId), LocalAuthEventKind.DEVICE_REVOKED, f.device("A"), f.actors.getValue("M"), "device_revocation")
            expect("B_observed_account_facts_unchanged", authV1SameObservedAccount(baseline.accounts.getValue("B"), after.accounts.getValue("B")))
            f.currentControl("B", "device-revoke-after")
            f.currentControl("M", "device-revoke-after")
            observe("scope_limit", "REAL_PUBLIC_DEVICE_REVOCATION_WITH_LIVE_SYNTHETIC_PEER_NO_PHYSICAL_CHANNEL_STREAM_OR_BUSINESS_COMMAND")
        }
        journal.slice("revoked_device_other_admitted_sign_in") {
            val moved = authenticated("A_password_login_on_other_admitted_device", f.signIn("A", "B", explicitDeviceMove = true))
            current(f, moved, "A", "B", "other_admitted_device")
            val after = f.snapshot("A-signed-in-on-other-admitted-device")
            coherentIssuance(f, after, moved.view, "A", "B", "other_admitted_device")
            expect("original_device_not_readmitted_by_fresh_login", !after.admissions.getValue("A").admitted &&
                after.admissions.getValue("B").admitted)
            val oldPeer = f.endpoint.current(f.access(moved, "A"))
            observe("fresh_access_over_revoked_device_result", oldPeer.authV1Code())
            expect("fresh_access_does_not_bypass_revoked_device", oldPeer == LocalSessionViewResult.Denied)
            authenticationRefused("fresh_refresh_over_revoked_device", f.rotate(moved, "A"))
            current(f, moved, "A", "B", "other_device_still_current")
            f.currentControl("B", "other-device-independent-B-control")
            observe("scope_limit", "ACTUAL_LOCAL_SIGNIN_ON_INITIAL_ADMITTED_PEER_NOT_READMISSION_PAIRING_OR_INSTALLED_UI")
        }
        val beforeClose = f.snapshot("device-revoked-before-real-close")
        f.closeService()
        journal.slice("device_revoke_durable_fresh_connection") {
            val after = f.snapshot("device-revoked-after-real-close")
            expect("revoked_admission_state_retained", !after.admissions.getValue("A").admitted &&
                after.admissions.getValue("A").revision == beforeClose.admissions.getValue("A").revision)
            expect("A_revocation_and_other_device_session_facts_retained", authV1SameObservedAccount(
                beforeClose.accounts.getValue("A"), after.accounts.getValue("A")))
            expect("other_device_remains_admitted", after.admissions.getValue("B").admitted)
            expect("B_observed_account_facts_retained", authV1SameObservedAccount(beforeClose.accounts.getValue("B"), after.accounts.getValue("B")))
            authEvent(after, checkNotNull(eventId), LocalAuthEventKind.DEVICE_REVOKED, f.device("A"), f.actors.getValue("M"), "device_revocation_after_close")
            observe("scope_limit", "REAL_SERVICE_CLOSE_FRESH_PROTECTED_READ_NOT_PROCESS_RESTART_OR_OLD_ENCRYPTED_CHANNEL")
        }
    }
}
