package com.ukkera.pos.database

import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Explicit non-JUnit executor of frozen auth-v1 assertion SLICES only.
 * No @Test: ordinary local regression discovery must never run these cases.
 * Python first freezes runtime inputs and proves a new network namespace.
 */
public object AuthV1Main {
    @JvmStatic
    public fun main(args: Array<String>): Unit {
        val exit = try {
            require(args.size == 1)
            val run = Path.of(args.single())
            require(run.isAbsolute && run.normalize() == run && Files.isDirectory(run) && !Files.isSymbolicLink(run))
            val request = Json.parseToJsonElement(Files.readString(run.resolve("request.json"))).jsonObject
            val invocation = request.getValue("invocation").jsonPrimitive.content
            require(invocation.matches(Regex("[0-9a-f]{32}")))
            val proof = Json.parseToJsonElement(Files.readString(run.resolve("network-proof.json"))).jsonObject
            require(proof.getValue("invocation").jsonPrimitive.content == invocation)
            require(proof.getValue("status").jsonPrimitive.content == "ISOLATED_NO_EXTERNAL_NETWORK")
            require(proof.getValue("namespace").jsonPrimitive.content == Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString())
            val frozenBytes = Files.readAllBytes(run.resolve("frozen/cases.json"))
            require(authV1Sha256(frozenBytes) == "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100")
            val wiringFile = request.getValue("wiring_file").jsonPrimitive.content
            require(wiringFile == "wiring-v3.json") // Fixed version, never an arbitrary path/runner override.
            val wiringBytes = Files.readAllBytes(run.resolve("wiring").resolve(wiringFile))
            require(authV1Sha256(wiringBytes) == request.getValue("wiring_sha256").jsonPrimitive.content)
            val design = Json.parseToJsonElement(frozenBytes.toString(Charsets.UTF_8)).jsonObject
            val wiring = Json.parseToJsonElement(wiringBytes.toString(Charsets.UTF_8)).jsonObject
            require(wiring.getValue("wiring_id").jsonPrimitive.content == "auth-v1-host-slices-3")
            val privateDirectory = run.resolve("private-fixture")
            Files.createDirectory(privateDirectory)
            AuthV1Journal(run, invocation, wiring).use { journal ->
                journal.event("runtime", "jvm-start", "STARTED_NOT_ACCEPTED", buildJsonObject {
                    put("java_version", System.getProperty("java.version"))
                    put("java_vm", System.getProperty("java.vm.name"))
                    put("os_name", System.getProperty("os.name"))
                    put("os_arch", System.getProperty("os.arch"))
                    put("network_namespace", proof.getValue("namespace"))
                    put("scope", "HOST_AUTH_CORE_SERVICE_SLICES_ONLY")
                })
                try {
                    runBlocking {
                        authV1ObserveFreshSchema(privateDirectory.resolve("fresh-schema.db"), run, journal)
                        val logout = AuthV1Fixture("logout", privateDirectory.resolve("logout.db"), design, journal)
                        try {
                            logout.start()
                            boundarySlices(logout, journal)
                            logoutSlices(logout, journal)
                        } finally { logout.dispose() }
                        val administrative = AuthV1Fixture("administrative", privateDirectory.resolve("administrative.db"), design, journal)
                        try {
                            administrative.start()
                            administrativeSlices(administrative, journal)
                        } finally { administrative.dispose() }
                        authV1ExpandedSessionSlices(privateDirectory, design, journal)
                        authV1ExpandedAdministrationSlices(privateDirectory, design, journal)
                        authV1DeviceSlices(privateDirectory, design, journal)
                    }
                    journal.requireAllSlices()
                    journal.event("terminal", "harness-finished", "PARTIAL_AUTH_SLICES_NOT_ACCEPTANCE")
                    0
                } catch (failure: Throwable) {
                    // Never emit production exception text, stack traces, passwords or tokens.
                    journal.event("terminal", "harness-aborted", "ABORTED_NOT_ACCEPTED", buildJsonObject {
                        put("sanitized_error_type", failure.javaClass.simpleName)
                        put("unvisited_slices", "NOT_RUN")
                    })
                    1
                }
            }
        } catch (_: Throwable) {
            System.err.println("AUTH_V1_EXECUTOR_ENTRY_OR_EVIDENCE_UNAVAILABLE; no acceptance result")
            2
        }
        if (exit == 0) println("PARTIAL_AUTH_SLICES_NOT_ACCEPTANCE")
        exitProcess(exit)
    }

    private suspend fun boundarySlices(f: AuthV1Fixture, journal: AuthV1Journal) {
        journal.slice("paired_without_bearer_current") {
            f.currentControl("A", "paired-negative-before")
            withMalformed(f.connections.getValue("A")) { credential ->
                val result = f.endpoint.current(credential)
                observe("current_result", result.authV1Code())
                expect("no_human_bearer_current_refused", result == LocalSessionViewResult.Denied)
            }
            f.currentControl("A", "paired-negative-after")
        }
        journal.slice("paired_without_bearer_admin") {
            val before = f.snapshot("before-paired-admin-denial")
            withMalformed(f.connections.getValue("M")) { credential ->
                val result = f.endpoint.revokeSession(credential, f.actors.getValue("B"), f.tokens("B").view.sessionId,
                    before.accounts.getValue("B").state.revision)
                observe("administration_result", result.authV1Code())
                expect("paired_device_is_not_human_administrator", result == LocalCredentialAdministrationResult.Denied)
            }
            val after = f.snapshot("after-paired-admin-denial")
            expect("target_B_facts_unchanged", before.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            f.currentControl("B", "paired-admin-denial-after")
        }
        journal.slice("unadmitted_valid_password") {
            f.currentControl("A", "unadmitted-password-before")
            val result = f.tryValidPasswordOnUnadmittedPeer()
            observe("sign_in_result", result.authV1Code())
            if (result is LocalAuthenticationResult.Authenticated) result.tokens.destroy()
            expect("valid_password_cannot_admit_device", result is LocalAuthenticationResult.Denied &&
                result.reason == LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
            f.currentControl("A", "unadmitted-password-after")
        }
        journal.slice("unadmitted_valid_access") {
            val result = f.endpoint.current(f.access("A", "UNADMITTED"))
            observe("current_result", result.authV1Code())
            expect("real_issued_access_cannot_admit_device", result == LocalSessionViewResult.Denied)
            f.currentControl("A", "unadmitted-access-after")
        }
        journal.slice("wrong_branch_peer") {
            val result = f.endpoint.current(f.access("A", "WRONG_BRANCH"))
            observe("current_result", result.authV1Code())
            expect("wrong_branch_descriptor_refused_without_view", result == LocalSessionViewResult.Denied)
            f.currentControl("A", "wrong-branch-after")
        }
        journal.slice("tampered_access") {
            val tampered = f.tokens("A").access.useBytes { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                OpaqueCredential(bytes)
            }
            try {
                val result = f.endpoint.current(LocalGatewayCredentials(f.connections.getValue("A"), tampered))
                observe("current_result", result.authV1Code())
                expect("tampered_issued_access_refused_without_view", result == LocalSessionViewResult.Denied)
            } finally { tampered.destroy() }
            f.currentControl("A", "tampered-after")
        }
        journal.slice("malformed_access") {
            withMalformed(f.connections.getValue("A")) { credential ->
                val result = f.endpoint.current(credential)
                observe("current_result", result.authV1Code())
                expect("malformed_access_refused_without_view", result == LocalSessionViewResult.Denied)
            }
            f.currentControl("A", "malformed-after")
        }
        journal.slice("unauthorized_cross_user_revoke") {
            val before = f.snapshot("before-unauthorized-cross-user-revoke")
            val result = f.endpoint.revokeSession(f.access("A"), f.actors.getValue("B"), f.tokens("B").view.sessionId,
                before.accounts.getValue("B").state.revision)
            observe("administration_result", result.authV1Code())
            expect("ordinary_A_refused_without_private_target_result", result == LocalCredentialAdministrationResult.Denied)
            val after = f.snapshot("after-unauthorized-cross-user-revoke")
            expect("target_B_facts_unchanged", before.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            f.currentControl("B", "cross-user-denial-after")
        }
    }

    private suspend fun logoutSlices(f: AuthV1Fixture, journal: AuthV1Journal) {
        val baseline = f.snapshot("before-logout")
        val session = f.tokens("A").view.sessionId
        journal.slice("logout_access_refresh") {
            f.currentControl("A", "logout-before")
            f.currentControl("B", "logout-before")
            val result = f.endpoint.logout(f.access("A"))
            observe("logout_result", result.authV1Code())
            expect("local_logout_committed", result == LocalSignOutResult.Revoked)
            oldCredentialsDenied(f)
            f.currentControl("B", "logout-after")
        }
        journal.slice("duplicate_logout") {
            val before = f.snapshot("after-first-logout")
            val result = f.endpoint.logout(f.access("A"))
            observe("duplicate_logout_result", result.authV1Code())
            // Frozen behavior specifies one logical effect, not the duplicate reply code.
            expect("duplicate_not_busy_or_uncertain", result == LocalSignOutResult.Revoked || result == LocalSignOutResult.Denied)
            val after = f.snapshot("after-duplicate-logout")
            expect("A_account_still_enabled", after.accounts.getValue("A").state.enabled)
            expect("A_credential_not_reset", after.accounts.getValue("A").credentialPresent &&
                after.accounts.getValue("A").credentialRevision == baseline.accounts.getValue("A").credentialRevision)
            expect("same_retained_session_remains_revoked", after.accounts.getValue("A").state.sessions.singleOrNull {
                it.sessionId == session
            }?.status == SessionStatus.REVOKED)
            expect("no_additional_session_history_effect", before.accounts.getValue("A").state.sessions ==
                after.accounts.getValue("A").state.sessions)
            // Audit record cardinality is not a frozen oracle for logical revocation
            // cardinality. Observe it, but prove the effect through retained session
            // facts above rather than inventing an exactly-one-audit-row requirement.
            observe("logout_audit_events_for_target", JsonPrimitive(after.events.count {
                it.kind == LocalAuthEventKind.LOGGED_OUT && it.targetId == f.actors.getValue("A") && it.sessionId == session
            }))
            expect("unrelated_B_facts_unchanged", baseline.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            f.currentControl("B", "duplicate-logout-after")
        }
        f.closeService()
        journal.slice("logout_durable_fresh_connection") {
            val after = f.snapshot("after-real-close-logout")
            expect("committed_revocation_present_after_close", after.accounts.getValue("A").state.sessions.singleOrNull {
                it.sessionId == session
            }?.status == SessionStatus.REVOKED)
            expect("A_account_not_disabled", after.accounts.getValue("A").state.enabled)
            expect("B_committed_facts_preserved", baseline.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            observe("scope_limit", "FRESH_CONNECTION_AFTER_REAL_CLOSE_NOT_PROCESS_RESTART_OR_OLD_ENCRYPTED_CHANNEL")
        }
    }

    private suspend fun administrativeSlices(f: AuthV1Fixture, journal: AuthV1Journal) {
        val baseline = f.snapshot("before-authorized-revoke")
        val session = f.tokens("A").view.sessionId
        journal.slice("authorized_revoke_access_refresh") {
            f.currentControl("A", "authorized-revoke-before")
            f.currentControl("M", "authorized-revoke-before")
            f.currentControl("B", "authorized-revoke-before")
            val result = f.endpoint.revokeSession(f.access("M"), f.actors.getValue("A"), session,
                baseline.accounts.getValue("A").state.revision)
            observe("administration_result", result.authV1Code())
            expect("authorized_M_revocation_committed", result is LocalCredentialAdministrationResult.Applied)
            oldCredentialsDenied(f)
            val after = f.snapshot("after-authorized-revoke")
            val eventId = (result as LocalCredentialAdministrationResult.Applied).eventId
            observe("committed_event_id", eventId.value)
            expect("protected_event_binds_M_A_and_target_session", after.events.singleOrNull { it.eventId == eventId }?.let {
                it.kind == LocalAuthEventKind.SESSION_REVOKED && it.targetId == f.actors.getValue("A") &&
                    it.operatorId == f.actors.getValue("M") && it.sessionId == session
            } == true)
            expect("unrelated_B_facts_unchanged", baseline.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            f.currentControl("B", "authorized-revoke-after")
        }
        f.closeService()
        journal.slice("authorized_revoke_durable_fresh_connection") {
            val after = f.snapshot("after-real-close-authorized-revoke")
            expect("committed_revocation_present_after_close", after.accounts.getValue("A").state.sessions.singleOrNull {
                it.sessionId == session
            }?.status == SessionStatus.REVOKED)
            expect("A_account_not_disabled", after.accounts.getValue("A").state.enabled)
            expect("B_committed_facts_preserved", baseline.accounts.getValue("B").json() == after.accounts.getValue("B").json())
            observe("scope_limit", "FRESH_CONNECTION_AFTER_REAL_CLOSE_NOT_PROCESS_RESTART_OR_OLD_ENCRYPTED_CHANNEL")
        }
    }

    private suspend fun AuthV1Slice.oldCredentialsDenied(f: AuthV1Fixture) {
        val current = f.endpoint.current(f.access("A"))
        observe("old_access_current_result", current.authV1Code())
        expect("old_access_refused", current == LocalSessionViewResult.Denied)
        val refresh = f.endpoint.refresh(f.refresh("A"))
        observe("old_refresh_result", refresh.authV1Code())
        if (refresh is LocalAuthenticationResult.Authenticated) refresh.tokens.destroy()
        expect("old_refresh_refused", refresh is LocalAuthenticationResult.Denied &&
            refresh.reason == LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
    }

    private suspend fun <T> withMalformed(
        connectionId: com.ukkera.pos.types.DomainId,
        block: suspend (LocalGatewayCredentials) -> T,
    ): T {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val bearer = try { OpaqueCredential(bytes) } finally { bytes.fill(0) }
        return try { block(LocalGatewayCredentials(connectionId, bearer)) } finally { bearer.destroy() }
    }
}

internal fun LocalSignOutResult.authV1Code(): String = when (this) {
    LocalSignOutResult.Revoked -> "REVOKED"
    LocalSignOutResult.Denied -> "DENIED"
    LocalSignOutResult.Unavailable -> "UNAVAILABLE"
    LocalSignOutResult.Busy -> "BUSY"
    else -> "OTHER_NONACCEPTING_${javaClass.simpleName}"
}
