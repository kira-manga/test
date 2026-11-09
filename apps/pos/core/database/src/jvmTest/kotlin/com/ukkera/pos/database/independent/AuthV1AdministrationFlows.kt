package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Only actual public M-authenticated reset/disable paths. The initial ceremony is already CLOSED. */
internal suspend fun authV1ExpandedAdministrationSlices(directory: Path, design: JsonObject, journal: AuthV1Journal) {
    authV1WithFixture(directory, design, journal, "credential-reset") { f ->
        val baseline = f.snapshot("before-reset-flows")
        val initialRevision = baseline.accounts.getValue("A").state.revision
        lateinit var established: LocalSessionTokens
        var resetEvent: DomainId? = null
        f.withFreshPassword { newPassword ->
            journal.slice("unauthorized_credential_reset") {
                val result = f.endpoint.resetCredential(f.access("B"), f.actors.getValue("A"), initialRevision, newPassword)
                observe("ordinary_cashier_reset_result", result.authV1Code())
                expect("ordinary_B_cannot_reset_A", result == LocalCredentialAdministrationResult.Denied)
                val after = f.snapshot("after-unauthorized-reset")
                expect("A_observed_credential_revision_grants_sessions_unchanged", authV1SameObservedAccount(
                    baseline.accounts.getValue("A"), after.accounts.getValue("A")))
                f.currentControl("A", "unauthorized-reset-after")
                f.currentControl("M", "unauthorized-reset-after")
                observe("proof_scope", "CURRENT_ORDINARY_VS_DELEGATED_HUMAN_CREDENTIAL_NOT_FULL_ACTIVATION_PROOF_MATRIX")
            }
            journal.slice("authorized_reset_old_and_new_credentials") {
                resetEvent = applied("authorized_M_reset", f.endpoint.resetCredential(f.access("M"), f.actors.getValue("A"),
                    initialRevision, newPassword))
                val committed = f.snapshot("after-authorized-reset-before-relogin")
                val a = committed.accounts.getValue("A")
                expect("same_enabled_account_has_new_credential_revision", a.state.enabled && a.credentialPresent &&
                    a.state.actorId == f.actors.getValue("A") && a.state.scope == f.scope &&
                    a.credentialRevision != baseline.accounts.getValue("A").credentialRevision)
                expect("all_prior_A_sessions_are_retained_revoked", baseline.accounts.getValue("A").state.sessions.all { prior ->
                    a.state.sessions.singleOrNull { it.sessionId == prior.sessionId } == prior.copy(status = SessionStatus.REVOKED)
                } && a.authV1Active().isEmpty())
                authEvent(committed, checkNotNull(resetEvent), LocalAuthEventKind.CREDENTIAL_RESET, f.actors.getValue("A"), f.actors.getValue("M"), "reset")
                revokedCredentials(f, f.tokens("A"), "A", "reset")
                authenticationRefused("old_password_after_reset", f.signIn("A"))
                established = authenticated("new_password_after_reset", f.signInWith("A", newPassword))
                current(f, established, "A", "A", "new_credential")
                val after = f.snapshot("after-reset-new-password-login")
                coherentIssuance(f, after, established.view, "A", "A", "new_credential")
                expect("only_new_credential_session_active", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                    listOf(established.view.sessionId))
                expect("reset_does_not_change_A_policy", authV1SamePolicy(baseline.accounts.getValue("A").policy, after.accounts.getValue("A").policy))
                expect("unrelated_B_observed_facts_unchanged", authV1SameObservedAccount(baseline.accounts.getValue("B"), after.accounts.getValue("B")))
                f.currentControl("B", "authorized-reset-after")
                observe("reset_policy", "F_TIME_EXPLICIT_REVOKE_ALL_FIXTURE_D_AUTH_CREDENTIAL_LIFECYCLE_NOT_CLEARED")
            }
            f.withFreshPassword { stalePassword ->
                journal.slice("stale_reset_does_not_change_target") {
                    val before = f.snapshot("before-stale-reset")
                    val result = f.endpoint.resetCredential(f.access("M"), f.actors.getValue("A"), initialRevision, stalePassword)
                    observe("stale_revision_reset_result", result.authV1Code())
                    expect("stale_reset_authorization_refused", result == LocalCredentialAdministrationResult.Denied)
                    val after = f.snapshot("after-stale-reset")
                    expect("target_observed_credential_revision_grants_sessions_unchanged", authV1SameObservedAccount(
                        before.accounts.getValue("A"), after.accounts.getValue("A")))
                    current(f, established, "A", "A", "new_credential_still_current")
                    observe("uncovered_proof_variants", "WRONG_BRANCH_TARGET_SEPARATE_ACTIVATION_PROOF_AND_CRASH")
                }
            }
        }
        val beforeClose = f.snapshot("reset-before-real-close")
        f.closeService()
        journal.slice("reset_durable_fresh_connection") {
            val after = f.snapshot("reset-after-real-close")
            expect("committed_reset_and_new_session_facts_retained", authV1SameObservedAccount(beforeClose.accounts.getValue("A"),
                after.accounts.getValue("A")))
            expect("old_A_sessions_still_revoked", baseline.accounts.getValue("A").state.sessions.all { prior ->
                after.accounts.getValue("A").state.sessions.singleOrNull { it.sessionId == prior.sessionId }?.status == SessionStatus.REVOKED
            })
            authEvent(after, checkNotNull(resetEvent), LocalAuthEventKind.CREDENTIAL_RESET, f.actors.getValue("A"), f.actors.getValue("M"), "reset_after_close")
            expect("unrelated_B_observed_facts_retained", authV1SameObservedAccount(beforeClose.accounts.getValue("B"), after.accounts.getValue("B")))
            observe("scope_limit", "FRESH_PROTECTED_READ_AFTER_REAL_CLOSE_NO_RESTART_OR_POST_RESTART_OLD_BEARER_PROBE")
        }
    }

    authV1WithFixture(directory, design, journal, "concurrent-credential-resets") { f ->
        f.withFreshPassword { firstPassword ->
            f.withFreshPassword { secondPassword ->
                journal.slice("concurrent_reset_same_revision") {
                    val before = f.snapshot("before-two-reset-completions")
                    val revision = before.accounts.getValue("A").state.revision
                    val candidates = listOf(firstPassword, secondPassword)
                    val results = authV1Concurrent(
                        { f.endpoint.resetCredential(f.access("M"), f.actors.getValue("A"), revision, firstPassword) },
                        { f.endpoint.resetCredential(f.access("M"), f.actors.getValue("A"), revision, secondPassword) },
                    )
                    observe("reset_results", JsonArray(results.map { JsonPrimitive(it.authV1Code()) }))
                    expect("reset_outcomes_applied_or_stale_refusal", results.all {
                        it is LocalCredentialAdministrationResult.Applied || it == LocalCredentialAdministrationResult.Denied
                    })
                    val applied = results.withIndex().filter { it.value is LocalCredentialAdministrationResult.Applied }
                    expect("one_reset_completes_for_same_authorized_revision", applied.size == 1)
                    val winner = applied.single()
                    val afterResets = f.snapshot("after-two-reset-completions")
                    val a = afterResets.accounts.getValue("A")
                    expect("one_coherent_new_credential_with_no_old_active_sessions", a.credentialPresent && a.state.enabled &&
                        a.credentialRevision != before.accounts.getValue("A").credentialRevision && a.authV1Active().isEmpty() &&
                        before.accounts.getValue("A").state.sessions.all { prior ->
                            a.state.sessions.singleOrNull { it.sessionId == prior.sessionId } == prior.copy(status = SessionStatus.REVOKED)
                        })
                    authEvent(afterResets, (winner.value as LocalCredentialAdministrationResult.Applied).eventId,
                        LocalAuthEventKind.CREDENTIAL_RESET, f.actors.getValue("A"), f.actors.getValue("M"), "winning_reset")
                    revokedCredentials(f, f.tokens("A"), "A", "parallel_reset")
                    authenticationRefused("old_password_after_parallel_reset", f.signIn("A"))
                    authenticationRefused("unapplied_candidate_password", f.signInWith("A", candidates[1 - winner.index]))
                    val signedIn = authenticated("winning_candidate_password", f.signInWith("A", candidates[winner.index]))
                    current(f, signedIn, "A", "A", "winning_candidate")
                    val afterLogin = f.snapshot("after-winning-reset-password-login")
                    coherentIssuance(f, afterLogin, signedIn.view, "A", "A", "winning_candidate")
                    expect("reset_race_did_not_change_A_policy", authV1SamePolicy(before.accounts.getValue("A").policy,
                        afterLogin.accounts.getValue("A").policy))
                    observe("variant_scope", "TWO_RESET_COMPLETIONS_SUBSET_NO_CONCURRENT_OLD_PASSWORD_LOGIN_OR_CRASH_V_RACE_NOT_FULLY_RUN")
                }
            }
        }
    }

    authV1WithFixture(directory, design, journal, "unactivated-profile",
        AuthV1FixtureSetup(initiallySignedIn = setOf("B", "M"), includeUnactivatedU = true)) { f ->
        journal.slice("unactivated_profile_cannot_authenticate") {
            val before = f.snapshot("unactivated-profile-before-login")
            val u = before.accounts.getValue("U")
            expect("initial_U_has_profile_without_credential_or_session", !u.credentialPresent && u.state.sessions.isEmpty() &&
                u.state.actorId == f.id("U_UNACTIVATED_B1"))
            authenticationRefused("unactivated_profile_login", f.signIn("U", "A"))
            val after = f.snapshot("unactivated-profile-after-refusal")
            expect("profile_existence_did_not_issue_or_enable_credential", authV1SameObservedAccount(u, after.accounts.getValue("U")))
            f.currentControl("M", "unactivated-profile-control")
            observe("initial_provisioning_scope", "EXPLICIT_NULL_CREDENTIAL_FIXTURE_NOT_MANAGER_ENROLLMENT_OR_DIRECTORY_SYNC_UNDER_TEST")
        }
        f.withFreshPassword { password ->
            journal.slice("authorized_unactivated_credential_establishment") {
                val before = f.snapshot("before-U-credential-establishment")
                val event = applied("M_establishes_U_credential", f.endpoint.resetCredential(f.access("M"), f.actors.getValue("U"),
                    before.accounts.getValue("U").state.revision, password))
                val established = f.snapshot("after-U-credential-establishment")
                expect("same_U_profile_now_has_protected_credential", established.accounts.getValue("U").credentialPresent &&
                    established.accounts.getValue("U").state.actorId == before.accounts.getValue("U").state.actorId)
                authEvent(established, event, LocalAuthEventKind.CREDENTIAL_RESET, f.actors.getValue("U"), f.actors.getValue("M"), "U_establishment")
                val signedIn = authenticated("U_real_new_password_login", f.signInWith("U", password, "A"))
                current(f, signedIn, "U", "A", "U_current")
                val after = f.snapshot("after-U-first-real-login")
                coherentIssuance(f, after, signedIn.view, "U", "A", "U_first_login")
                expect("A_profile_was_not_replaced_by_U", authV1SameObservedAccount(before.accounts.getValue("A"), after.accounts.getValue("A")))
                f.currentControl("B", "U-establishment-after")
                observe("activation_scope", "REAL_DELEGATED_RESET_OF_INITIAL_NULL_CREDENTIAL_NOT_SEPARATE_PROOF_CEREMONY_OR_REACTIVATION")
            }
        }
    }

    authV1WithFixture(directory, design, journal, "account-disable") { f ->
        val baseline = f.snapshot("before-disable")
        var disableEvent: DomainId? = null
        journal.slice("authorized_disable_auth_paths") {
            // Frozen F-AUTH-BASE gives ordinary B no account administration permission.
            val ordinary = f.endpoint.disableAccount(f.access("B"), f.actors.getValue("A"), baseline.accounts.getValue("A").state.revision)
            observe("ordinary_B_disable_result", ordinary.authV1Code())
            expect("ordinary_B_without_account_rights_refused", ordinary == LocalCredentialAdministrationResult.Denied)
            val before = f.snapshot("after-ordinary-disable-refusal")
            expect("ordinary_attempt_preserves_A_observed_facts", authV1SameObservedAccount(baseline.accounts.getValue("A"), before.accounts.getValue("A")))
            f.currentControl("A", "before-authorized-disable")
            disableEvent = applied("M_disable", f.endpoint.disableAccount(f.access("M"), f.actors.getValue("A"), before.accounts.getValue("A").state.revision))
            revokedCredentials(f, f.tokens("A"), "A", "disable")
            authenticationRefused("disabled_A_password_login", f.signIn("A"))
            val after = f.snapshot("after-M-disable")
            val a = after.accounts.getValue("A")
            expect("disabled_A_has_no_active_session", !a.state.enabled && a.authV1Active().isEmpty())
            expect("prior_A_session_identity_is_preserved_revoked", baseline.accounts.getValue("A").state.sessions.all { prior ->
                a.state.sessions.singleOrNull { it.sessionId == prior.sessionId } == prior.copy(status = SessionStatus.REVOKED)
            })
            authEvent(after, checkNotNull(disableEvent), LocalAuthEventKind.ACCOUNT_DISABLED, f.actors.getValue("A"), f.actors.getValue("M"), "disable")
            expect("B_observed_facts_unchanged", authV1SameObservedAccount(baseline.accounts.getValue("B"), after.accounts.getValue("B")))
            f.currentControl("B", "disable-after")
            f.currentControl("M", "disable-after")
            observe("current_account_scope", "ACTUAL_AUTH_CURRENT_REFRESH_SIGNIN_ONLY_NO_REGISTERED_BUSINESS_QUERY_STREAM_OR_COMMAND")
        }
        val beforeClose = f.snapshot("disabled-before-real-close")
        f.closeService()
        journal.slice("disable_durable_fresh_connection") {
            val after = f.snapshot("disabled-after-real-close")
            expect("disabled_state_and_observed_revocation_history_retained", !after.accounts.getValue("A").state.enabled &&
                authV1SameObservedAccount(beforeClose.accounts.getValue("A"), after.accounts.getValue("A")))
            authEvent(after, checkNotNull(disableEvent), LocalAuthEventKind.ACCOUNT_DISABLED, f.actors.getValue("A"), f.actors.getValue("M"), "disable_after_close")
            expect("B_observed_facts_retained", authV1SameObservedAccount(beforeClose.accounts.getValue("B"), after.accounts.getValue("B")))
            observe("scope_limit", "REAL_SERVICE_CLOSE_FRESH_CONNECTION_NOT_PROCESS_RESTART_LOST_NOTIFICATION_OR_STALE_PROFILE_ACTIVATION")
            observe("reactivation", "NOT_EXERCISED_NO_REOPENED_SYNTHETIC_MANAGER_CEREMONY")
        }
    }
}
