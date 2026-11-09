package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Frozen I005/I008/I009/I011/I012 host slices; no installed client, clock or crash qualification. */
internal suspend fun authV1ExpandedSessionSlices(directory: Path, design: JsonObject, journal: AuthV1Journal) {
    val unloggedA = AuthV1FixtureSetup(initiallySignedIn = setOf("B", "M"))
    authV1WithFixture(directory, design, journal, "sign-in", unloggedA) { f ->
        lateinit var issued: LocalSessionTokens
        journal.slice("sign_in_current_identity") {
            val before = f.snapshot("before-A-sign-in")
            expect("A_has_no_seeded_or_initial_session", before.accounts.getValue("A").state.sessions.isEmpty())
            issued = authenticated("sign_in", f.signIn("A"))
            val view = current(f, issued, "A", "A", "signed_in_current")
            expect("returned_policy_is_same_A_B1_policy", view.policy.actorId == f.actors.getValue("A") &&
                view.policy.scope == f.scope && view.policy.sessionPolicy.maximumConcurrentSessions == 1)
            expect("declared_ordinary_read_grants_only", view.policy.grants.size == 2 &&
                view.policy.grants.map { it.permission.action }.toSet() == setOf(SecurityAction.ORDER_READ, SecurityAction.CASH_READ) &&
                view.policy.grants.all { it.permission.verb == PermissionVerb.PERFORM && it.scope == f.scope &&
                    it.effect == GrantEffect.ALLOW && it.ownerAccess == OwnerAccess.OWN_ONLY &&
                    it.namedSubjectId == f.actors.getValue("A") })
            observe("numeric_limits_fixture", "F_LIMITS_NOT_EXERCISED_READ_ONLY_AUTH_SUBFIXTURE")
        }
        journal.slice("sign_in_durable_issuance") {
            val after = f.snapshot("after-A-sign-in-return")
            coherentIssuance(f, after, issued.view, "A", "A", "sign_in")
            expect("one_committed_A_session", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                listOf(issued.view.sessionId))
            expect("committed_sign_in_audit_binds_A_session", after.events.any {
                it.kind == LocalAuthEventKind.SIGNED_IN && it.targetId == f.actors.getValue("A") &&
                    it.sessionId == issued.view.sessionId
            })
            observe("secret_storage_scope", "PROTECTED_MASTER_RECORD_PRESENCE_ONLY_NO_CLIENT_OS_STORAGE_OR_PLAINTEXT_SCAN")
        }
    }

    authV1WithFixture(directory, design, journal, "concurrent-sign-in", unloggedA) { f ->
        journal.slice("concurrent_default_sign_in") {
            val before = f.snapshot("before-simultaneous-default-logins")
            expect("A_starts_without_active_session", before.accounts.getValue("A").authV1Active().isEmpty())
            val destinations = listOf("A", "B")
            val results = authV1Concurrent({ f.signIn("A", "A") }, { f.signIn("A", "B") })
            observe("results", JsonArray(results.map { JsonPrimitive(it.authV1Code()) }))
            expect("no_busy_unavailable_or_uncertain_result", results.all(::authV1ConclusiveAuthentication))
            expect("at_least_one_real_sign_in_success", results.any { it is LocalAuthenticationResult.Authenticated })
            val after = f.snapshot("after-simultaneous-default-logins")
            val active = after.accounts.getValue("A").authV1Active()
            expect("default_policy_has_one_active_A_session", active.size == 1)
            val currentIds = mutableSetOf<com.ukkera.pos.types.DomainId>()
            for ((index, result) in results.withIndex()) {
                if (result !is LocalAuthenticationResult.Authenticated) continue
                val token = result.tokens
                coherentIssuance(f, after, token.view, "A", destinations[index], "candidate_$index", requireActive = false)
                val now = f.endpoint.current(f.access(token, destinations[index]))
                observe("candidate_${index}_current_result", now.authV1Code())
                expect("candidate_${index}_current_or_revoked", now is LocalSessionViewResult.Current || now == LocalSessionViewResult.Denied)
                if (now is LocalSessionViewResult.Current) {
                    expect("candidate_${index}_current_is_A", now.view.actorId == f.actors.getValue("A") &&
                        now.view.scope == f.scope && now.view.sessionId == token.view.sessionId)
                    currentIds.add(now.view.sessionId)
                }
                expect("candidate_${index}_audit_matches_actor_session", after.events.any {
                    it.kind == LocalAuthEventKind.SIGNED_IN && it.targetId == f.actors.getValue("A") &&
                        it.sessionId == token.view.sessionId
                })
            }
            expect("surviving_durable_session_has_real_current_token", currentIds == active.map { it.sessionId }.toSet())
            expect("unrelated_B_observed_facts_unchanged", authV1SameObservedAccount(before.accounts.getValue("B"), after.accounts.getValue("B")))
            f.currentControl("B", "concurrent-sign-in-after")
            observe("schedule", "TWO_BARRIER_RELEASED_REAL_ENDPOINT_CALLS_NOT_EXHAUSTIVE_INTERNAL_INTERLEAVINGS")
            observe("business_effects", "F_TRADE_NOT_EXERCISED")
        }
    }

    authV1WithFixture(directory, design, journal, "sign-in-reply-discard", unloggedA) { f ->
        journal.slice("sign_in_reply_discard_relogin") {
            val lost = authenticated("intercepted_sign_in", f.signIn("A"))
            val lostView = lost.view
            f.discardTokens(lost)
            observe("loss_boundary", "EXECUTOR_DESTROYS_RETURNED_TOKENS_AFTER_ENDPOINT_RETURN_NOT_RPC_DROP_OR_RESTART")
            val committed = f.snapshot("discarded-sign-in-return")
            coherentIssuance(f, committed, lostView, "A", "A", "discarded_sign_in")
            val recovered = authenticated("explicit_local_relogin", f.signIn("A", explicitDeviceMove = true))
            current(f, recovered, "A", "A", "recovered")
            val after = f.snapshot("after-discarded-sign-in-local-relogin")
            coherentIssuance(f, after, recovered.view, "A", "A", "recovered")
            expect("safe_relogin_respects_one_session_cap", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                listOf(recovered.view.sessionId))
            expect("same_A_identity_preserved", after.accounts.getValue("A").state.actorId == f.actors.getValue("A"))
            f.currentControl("B", "sign-in-reply-discard-after")
            observe("recovery_scope", "EXPLICIT_PASSWORD_RELOGIN_SERVICE_PATH_NO_CLIENT_RECOVERY_UI_QUALIFICATION")
        }
    }

    authV1WithFixture(directory, design, journal, "session-move") { f ->
        journal.slice("default_move_requires_confirmation") {
            val before = f.snapshot("before-unconfirmed-move")
            val result = f.signIn("A", "B")
            observe("unconfirmed_move_result", result.authV1Code())
            expect("unconfirmed_default_move_requires_explicit_flow", result is LocalAuthenticationResult.Denied &&
                result.reason == LocalAuthenticationDenial.EXPLICIT_DEVICE_MOVE_REQUIRED)
            val after = f.snapshot("after-unconfirmed-move")
            expect("original_A_observed_session_and_policy_unchanged", authV1SameObservedAccount(before.accounts.getValue("A"), after.accounts.getValue("A")))
            current(f, f.tokens("A"), "A", "A", "original_still_current")
        }
        journal.slice("confirmed_device_move") {
            val moved = authenticated("confirmed_move", f.signIn("A", "B", explicitDeviceMove = true))
            current(f, moved, "A", "B", "moved")
            revokedCredentials(f, f.tokens("A"), "A", "move")
            val after = f.snapshot("after-confirmed-move")
            coherentIssuance(f, after, moved.view, "A", "B", "move")
            expect("only_confirmed_destination_is_active", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                listOf(moved.view.sessionId))
            observe("explicit_flow_scope", "PUBLIC_EXPLICIT_MOVE_FLAG_NO_INSTALLED_CONFIRMATION_UI_OR_APPROVAL_TOKEN_CLAIM")
        }
    }

    authV1WithFixture(directory, design, journal, "concurrent-moves") { f ->
        journal.slice("concurrent_confirmed_device_moves") {
            val destinations = listOf("B", "M")
            val results = authV1Concurrent(
                { f.signIn("A", "B", explicitDeviceMove = true) },
                { f.signIn("A", "M", explicitDeviceMove = true) },
            )
            observe("results", JsonArray(results.map { JsonPrimitive(it.authV1Code()) }))
            expect("no_busy_unavailable_or_uncertain_move", results.all(::authV1ConclusiveAuthentication))
            expect("at_least_one_confirmed_move_succeeds", results.any { it is LocalAuthenticationResult.Authenticated })
            val after = f.snapshot("after-simultaneous-confirmed-moves")
            val active = after.accounts.getValue("A").authV1Active()
            expect("confirmed_moves_leave_one_active_session", active.size == 1)
            val currentIds = mutableSetOf<com.ukkera.pos.types.DomainId>()
            for ((index, result) in results.withIndex()) {
                if (result !is LocalAuthenticationResult.Authenticated) continue
                coherentIssuance(f, after, result.tokens.view, "A", destinations[index], "move_$index", requireActive = false)
                val now = f.endpoint.current(f.access(result.tokens, destinations[index]))
                observe("move_${index}_current_result", now.authV1Code())
                expect("move_${index}_current_or_revoked", now is LocalSessionViewResult.Current || now == LocalSessionViewResult.Denied)
                if (now is LocalSessionViewResult.Current) {
                    expect("move_${index}_current_identity", now.view.actorId == f.actors.getValue("A") && now.view.scope == f.scope &&
                        now.view.deviceId == f.device(destinations[index]) && now.view.sessionId == result.tokens.view.sessionId)
                    currentIds.add(now.view.sessionId)
                }
            }
            expect("one_returned_token_matches_surviving_session", currentIds == active.map { it.sessionId }.toSet())
            revokedCredentials(f, f.tokens("A"), "A", "original_before_moves")
            observe("schedule", "TWO_REAL_CONFIRMED_ENDPOINT_SUBMISSIONS_NO_INTERNAL_COMMIT_CUT_OR_RESTART")
            observe("uncovered", "UI_CONFIRMATION_APPROVAL_PROOF_F_TRADE_AND_OTHER_BRANCH_OBLIGATIONS")
        }
    }

    authV1WithFixture(directory, design, journal, "two-session-cap", AuthV1FixtureSetup(maximumConcurrentA = 2)) { f ->
        journal.slice("explicit_two_session_cap") {
            val second = authenticated("second_admitted_device", f.signIn("A", "B"))
            current(f, f.tokens("A"), "A", "A", "first")
            current(f, second, "A", "B", "second")
            val beforeThird = f.snapshot("two-active-before-third-login")
            expect("explicit_initial_policy_permits_two", beforeThird.accounts.getValue("A").policy.sessionPolicy.maximumConcurrentSessions == 2 &&
                beforeThird.accounts.getValue("A").authV1Active().map { it.sessionId }.toSet() ==
                setOf(f.tokens("A").view.sessionId, second.view.sessionId))
            val third = f.signIn("A", "M")
            observe("third_login_result", third.authV1Code())
            expect("third_interactive_session_is_refused", third is LocalAuthenticationResult.Denied &&
                third.reason == LocalAuthenticationDenial.SESSION_LIMIT)
            val afterThird = f.snapshot("after-third-login-refused")
            expect("third_attempt_preserves_two_observed_sessions", authV1SameObservedAccount(
                beforeThird.accounts.getValue("A"), afterThird.accounts.getValue("A")))
            current(f, f.tokens("A"), "A", "A", "first_after_third")
            current(f, second, "A", "B", "second_after_third")
            observe("policy_activation", "EXPLICIT_PREDECLARED_INITIAL_I009_VARIANT_NOT_MANAGER_ACTIVATION_UNDER_TEST")
            observe("uncovered", "OTHER_BRANCH_AND_ONE_INTERACTIVE_USER_PER_INSTALLED_UI")
        }
    }

    authV1WithFixture(directory, design, journal, "refresh") { f ->
        lateinit var origin: LocalRefreshIssuance
        journal.slice("refresh_wrong_device") {
            f.currentControl("A", "wrong-device-refresh-before")
            val before = f.snapshot("before-wrong-device-refresh")
            authenticationRefused("wrong_admitted_device_refresh", f.rotate(f.tokens("A"), "B"))
            val after = f.snapshot("after-wrong-device-refresh")
            expect("A_observed_facts_unchanged", authV1SameObservedAccount(before.accounts.getValue("A"), after.accounts.getValue("A")))
            expect("B_observed_facts_unchanged", authV1SameObservedAccount(before.accounts.getValue("B"), after.accounts.getValue("B")))
            f.currentControl("A", "wrong-device-refresh-after")
            f.currentControl("B", "wrong-device-refresh-after")
        }
        journal.slice("refresh_rotation_committed_lineage") {
            val before = f.snapshot("before-valid-refresh")
            origin = checkNotNull(before.accounts.getValue("A").issuances.getValue(f.tokens("A").view.sessionId).refresh)
            val rotated = authenticated("refresh", f.rotate(f.tokens("A"), "A"))
            current(f, rotated, "A", "A", "rotated")
            val after = f.snapshot("after-valid-refresh-return")
            coherentIssuance(f, after, rotated.view, "A", "A", "rotation")
            singleRefreshLineage(f, after, origin, "rotation")
            expect("one_current_successor_session", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                listOf(rotated.view.sessionId))
            expect("refresh_does_not_extend_original_work_deadline", rotated.view.workExpiresAt == f.tokens("A").view.workExpiresAt)
            expect("committed_rotation_audit_names_A_successor", after.events.any {
                it.kind == LocalAuthEventKind.REFRESHED && it.targetId == f.actors.getValue("A") &&
                    it.sessionId == rotated.view.sessionId
            })
            observe("old_token_denial", "NOT_ASSUMED_WHILE_D_AUTH_REFRESH_REPLAY_UNRESOLVED")
        }
        journal.slice("refresh_replay_no_scope_or_lifetime_gain") {
            val before = f.snapshot("before-old-refresh-replay")
            val result = f.rotate(f.tokens("A"), "A")
            observe("replay_result", result.authV1Code())
            expect("replay_has_conclusive_non_availability_result", authV1ConclusiveAuthentication(result))
            val after = f.snapshot("after-old-refresh-replay")
            singleRefreshLineage(f, after, origin, "replay")
            if (result is LocalAuthenticationResult.Authenticated) {
                coherentIssuance(f, after, result.tokens.view, "A", "A", "replay_return", requireActive = false)
                expect("returned_replay_token_does_not_gain_lifetime", result.tokens.view.workExpiresAt.bootId == origin.workStartedAt.bootId &&
                    result.tokens.view.workExpiresAt.elapsedMillis <= origin.expiresAtElapsedMillis)
            }
            expect("replay_keeps_active_policy_and_enablement", authV1SamePolicy(before.accounts.getValue("A").policy,
                after.accounts.getValue("A").policy) && before.accounts.getValue("A").state.enabled == after.accounts.getValue("A").state.enabled)
            expect("B_observed_facts_unchanged", authV1SameObservedAccount(before.accounts.getValue("B"), after.accounts.getValue("B")))
            f.currentControl("B", "refresh-replay-after")
            observe("not_exercised", "REPLAY_WINDOW_TIMING_THROTTLE_STATE_AND_BUSINESS_LEDGERS")
        }
    }

    authV1WithFixture(directory, design, journal, "concurrent-refresh") { f ->
        journal.slice("concurrent_refresh_single_lineage") {
            f.currentControl("A", "parallel-refresh-before")
            val before = f.snapshot("before-parallel-refresh")
            val origin = checkNotNull(before.accounts.getValue("A").issuances.getValue(f.tokens("A").view.sessionId).refresh)
            val results = authV1Concurrent({ f.rotate(f.tokens("A"), "A") }, { f.rotate(f.tokens("A"), "A") })
            observe("results", JsonArray(results.map { JsonPrimitive(it.authV1Code()) }))
            expect("no_busy_unavailable_or_uncertain_refresh", results.all(::authV1ConclusiveAuthentication))
            expect("at_least_one_actual_rotation_success", results.any { it is LocalAuthenticationResult.Authenticated })
            val after = f.snapshot("after-parallel-refresh")
            singleRefreshLineage(f, after, origin, "parallel")
            val currentIds = mutableSetOf<com.ukkera.pos.types.DomainId>()
            for ((index, result) in results.withIndex()) {
                if (result !is LocalAuthenticationResult.Authenticated) continue
                coherentIssuance(f, after, result.tokens.view, "A", "A", "parallel_$index", requireActive = false)
                expect("parallel_${index}_returned_work_deadline_not_extended", result.tokens.view.workExpiresAt.bootId == origin.workStartedAt.bootId &&
                    result.tokens.view.workExpiresAt.elapsedMillis <= origin.expiresAtElapsedMillis)
                val now = f.endpoint.current(f.access(result.tokens, "A"))
                observe("parallel_${index}_current_result", now.authV1Code())
                expect("parallel_${index}_current_or_revoked", now is LocalSessionViewResult.Current || now == LocalSessionViewResult.Denied)
                if (now is LocalSessionViewResult.Current) {
                    expect("parallel_${index}_current_has_original_actor_scope", now.view.actorId == f.actors.getValue("A") &&
                        now.view.scope == f.scope && now.view.sessionId == result.tokens.view.sessionId)
                    currentIds.add(now.view.sessionId)
                }
            }
            expect("at_most_one_current_successor_identity", currentIds.size <= 1)
            expect("current_successor_ids_match_surviving_protected_sessions", currentIds ==
                after.accounts.getValue("A").authV1Active().map { it.sessionId }.toSet())
            observe("replay_policy_limit", "ZERO_SURVIVORS_ALLOWED_IF_REPLAY_REVOKES_LINEAGE_NO_WINDOW_OUTCOME_INVENTED")
            observe("schedule", "TWO_BARRIER_RELEASED_REAL_ENDPOINT_CALLS_NOT_INTERNAL_COMMIT_FAULTS")
        }
    }

    authV1WithFixture(directory, design, journal, "refresh-reply-discard") { f ->
        journal.slice("refresh_reply_discard_relogin") {
            val before = f.snapshot("before-discarded-refresh")
            val origin = checkNotNull(before.accounts.getValue("A").issuances.getValue(f.tokens("A").view.sessionId).refresh)
            val lost = authenticated("intercepted_refresh", f.rotate(f.tokens("A"), "A"))
            val lostView = lost.view
            f.discardTokens(lost)
            observe("loss_boundary", "EXECUTOR_DESTROYS_RETURNED_TOKENS_AFTER_ENDPOINT_RETURN_NOT_RPC_DROP_OR_RESTART")
            val committed = f.snapshot("discarded-refresh-return")
            coherentIssuance(f, committed, lostView, "A", "A", "discarded_refresh")
            singleRefreshLineage(f, committed, origin, "discarded_refresh")
            val recovered = authenticated("explicit_password_relogin", f.signIn("A", explicitDeviceMove = true))
            current(f, recovered, "A", "A", "recovered")
            val after = f.snapshot("after-discarded-refresh-local-relogin")
            coherentIssuance(f, after, recovered.view, "A", "A", "recovered")
            expect("relogin_has_only_one_tracked_active_session", after.accounts.getValue("A").authV1Active().map { it.sessionId } ==
                listOf(recovered.view.sessionId))
            observe("new_work_lifetime", "PASSWORD_REAUTHENTICATION_NOT_A_REFRESH_EXTENSION")
            observe("client_recovery_ui_or_restart", "NOT_EXERCISED")
        }
    }

    authV1WithFixture(directory, design, journal, "logout-reply-discard") { f ->
        journal.slice("logout_reply_discard_duplicate") {
            val baseline = f.snapshot("before-discarded-logout")
            val result = f.endpoint.logout(f.access("A"))
            observe("intercepted_logout_result", result.authV1Code())
            expect("first_logout_committed", result == LocalSignOutResult.Revoked)
            observe("loss_boundary", "EXECUTOR_DISCARDS_RETURN_VALUE_NOT_RPC_DROP_OR_PENDING_CLIENT_INTENT")
            val beforeDuplicate = f.snapshot("discarded-logout-return")
            val repeated = f.endpoint.logout(f.access("A"))
            observe("duplicate_logout_result", repeated.authV1Code())
            expect("duplicate_is_logical_revoke_or_refusal", repeated == LocalSignOutResult.Revoked || repeated == LocalSignOutResult.Denied)
            val after = f.snapshot("after-discarded-logout-duplicate")
            val a = after.accounts.getValue("A")
            expect("same_retained_session_stays_revoked", a.state.sessions.singleOrNull {
                it.sessionId == f.tokens("A").view.sessionId
            }?.status == SessionStatus.REVOKED)
            expect("one_logical_session_effect", beforeDuplicate.accounts.getValue("A").state.sessions == a.state.sessions)
            expect("account_remains_enabled_with_same_credential_revision", a.state.enabled && a.credentialPresent &&
                a.credentialRevision == baseline.accounts.getValue("A").credentialRevision)
            expect("unrelated_B_observed_facts_unchanged", authV1SameObservedAccount(baseline.accounts.getValue("B"), after.accounts.getValue("B")))
            revokedCredentials(f, f.tokens("A"), "A", "logout")
            f.currentControl("B", "logout-reply-discard-after")
            observe("audit_cardinality_or_client_intent", "NOT_AN_ORACLE_OR_QUALIFIED_CLIENT_PATH")
        }
    }
}

/** Concurrent losers/replays may refuse; busy/provider/storage failure is never the tested outcome. */
private fun authV1ConclusiveAuthentication(result: LocalAuthenticationResult): Boolean =
    result is LocalAuthenticationResult.Authenticated ||
        result is LocalAuthenticationResult.Denied && result.reason != LocalAuthenticationDenial.SERVICE_BUSY
