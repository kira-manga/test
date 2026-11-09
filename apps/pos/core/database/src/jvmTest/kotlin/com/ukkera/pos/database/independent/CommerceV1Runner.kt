package com.ukkera.pos.database

import com.ukkera.pos.sales.store.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

/**
 * Explicit non-JUnit executor. No @Test and no production edits or replacement
 * checkout service. Inputs/assertions were frozen by /root/qa_commerce before any
 * production-source access. This main records observations only; the wrapper
 * compares named components to the unchanged frozen oracle and NEVER awards a
 * complete assertion/case/scenario PASS from this limited route.
 */
public object CommerceV1Runner {
    @JvmStatic
    public fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 2) { "commerce.runner.requires_input_and_output_paths" }
        val input = Json.parseToJsonElement(Files.readString(Path.of(args[0]))).jsonObject
        check(input.commerceText("frozen_cases_sha256") == "755831e35d5fb93bc2f48828d891b34f4147b103549a97fb05e5ee9716ea9d04")
        val binding = input.getValue("binding").jsonObject
        if (binding.commerceText("version") == "commerce-cash-binding-v2") {
            CommerceV2Runner.execute(input, Path.of(args[1]).toAbsolutePath().normalize())
            return@runBlocking
        }
        check(binding.commerceText("version") == "commerce-cash-binding-v1")
        val metadata = binding.getValue("metadata").jsonObject
        val directory = Path.of(args[1]).toAbsolutePath().normalize()
        val cases = input.getValue("cases").jsonArray.map { it.jsonObject }.associateBy { it.commerceText("case_id") }
        val variants = binding.getValue("independent_resets").jsonArray.map { it.jsonPrimitive.content }
        check(variants == listOf("V-VALID-SLICE", "V-DUPLICATE-DIRECT-SLICE", "V-MISMATCH-BODY-SLICE", "V-MISMATCH-ACTOR-SLICE"))
        var blocked = false
        for (family in listOf("CASH-01", "CASH-02")) {
            val familyBinding = binding.getValue("families").jsonObject.getValue(family).jsonObject
            val frozen = cases.getValue(familyBinding.commerceText("case_id"))
            for (variant in variants) {
                val sliceDirectory = directory.resolve(family).resolve(variant)
                val observations = CommerceV1Observations(sliceDirectory)
                observations.event("slice_begin", buildJsonObject {
                    put("case_id", frozen.commerceText("case_id")); put("variant_component", variant)
                    put("mode", "DIRECT-PRODUCTION-ENDPOINT-SYNTHETIC-TRUST")
                    put("whole_case_pass_allowed", false)
                })
                val fixture = CommerceV1Fixture(familyBinding, metadata, sliceDirectory.resolve("master.sqlite"), observations)
                try {
                    fixture.open()
                    CommerceV1CashExecution(fixture, frozen).execute(family, variant)
                    observations.event("slice_observed", buildJsonObject {
                        put("verdict", "OBSERVATIONS_ONLY")
                        put("ui_lan_fault_restore_platforms", "NOT_RUN")
                    })
                } catch (error: Throwable) {
                    blocked = true
                    observations.event("slice_error", buildJsonObject {
                        put("category", if (error is CommerceV1WiringBlocked) "FIXTURE_OR_BINDING_BLOCKED" else "EXECUTOR_ERROR")
                        put("error_type", error.javaClass.name)
                        if (error is CommerceV1WiringBlocked) put("safe_code", error.code)
                        put("frames", buildJsonArray { error.stackTrace.take(32).forEach { add(it.toString()) } })
                        put("exception_messages_omitted", "No arbitrary exception/password/credential dump")
                    })
                } finally {
                    try {
                        fixture.close()
                        if (Files.isRegularFile(fixture.databasePath)) {
                            val snapshot = observations.snapshot("final-after-close.json", fixture.databasePath, fixture.scope)
                            observations.event("fresh_read_after_production_close", buildJsonObject { put("snapshot", snapshot) })
                        }
                    } catch (_: CommerceV1RetirementRequired) {
                        // Do not open another service/connection or release custody after uncertain close.
                        observations.event("runner_requires_process_reap")
                        exitProcess(4)
                    } catch (error: Throwable) {
                        observations.event("close_or_final_read_error", buildJsonObject { put("error_type", error.javaClass.name) })
                        exitProcess(5)
                    }
                }
            }
        }
        if (blocked) exitProcess(2)
    }
}

private class CommerceV1CashExecution(private val fixture: CommerceV1Fixture, private val frozen: JsonObject) {
    private val observations get() = fixture.observations
    private val sales get() = LocalSalesService(fixture.service.endpoint)
    private val action = frozen.getValue("actions").jsonArray.single().jsonObject
    private val inputs = action.getValue("inputs").jsonObject

    suspend fun execute(family: String, variant: String) {
        for (drawer in listOf("DA", "DB")) {
            requireFixtureApplied(mutate("fixture-configure-$drawer", "M", LocalSalesCommands.cash(
                fixture.operation("configure-$drawer"), LocalCashRequestV1.ConfigureDrawer(fixture.id(drawer), "SYN-$drawer", Currency.EGP))),
                "configure-$drawer")
        }
        for (actor in listOf("A", "B")) {
            requireFixtureApplied(mutate("fixture-configure-$actor", "M", LocalSalesCommands.cash(
                fixture.operation("configure-$actor"), LocalCashRequestV1.ConfigureCustodian(fixture.id(actor)))), "configure-$actor")
        }
        if (family == "CASH-02") {
            val opening = frozen.getValue("fixture").jsonObject.getValue("open_sessions").jsonArray.single().jsonObject
            val dto = openRequest("A", "DA", "SA", "safe-float-A-fixture", opening.commerceText("float"), "evidence-fixture-open-A")
            requireFixtureApplied(mutate("fixture-open-A", "A", LocalSalesCommands.cash(fixture.operation("fixture-open-A"), dto)), "open-A")
        }
        val actor = action.commerceText("actor")
        val originalDto = openRequest(actor, inputs.commerceText("drawer"), fixture.binding.commerceText("session_for_original_action"),
            inputs.commerceText("source"), inputs.commerceText("counted_opening"), "evidence-open")
        val original = LocalSalesCommands.cash(OperationId(action.commerceText("operation_id")), originalDto)
        val originalResult = mutate("original", actor, original)
        if (originalResult !is MutationReply.Recorded ||
            (variant == "V-VALID-SLICE" && originalResult.committed != (family == "CASH-01"))) {
            // The frozen retry/mismatch matrix requires an established durable identity.
            // Do not manufacture that prerequisite after a Deferred/unknown outcome.
            observations.event("remaining_slice_precondition_unmet", buildJsonObject {
                put("reason", "Original durable identity or required active-custody cut not established")
                put("variant_component", variant)
                put("remaining_variant", "BLOCKED_NOT_EXERCISED")
            })
            return
        }
        when (variant) {
            "V-VALID-SLICE" -> {
                val extra = if (family == "CASH-01") {
                    MutationRequest(fixture.operation("assertion-extra-open"), original.command, original.payload)
                } else {
                    LocalSalesCommands.cash(fixture.operation("assertion-extra-open"), openRequest(
                        "A", "DB", "SA-DB-ATTEMPT", "safe-float-A-DB", inputs.commerceText("counted_opening"), "evidence-A-DB"))
                }
                mutate("assertion-extra-open", "A", extra)
            }
            "V-DUPLICATE-DIRECT-SLICE" -> {
                mutate("duplicate-1", actor, original)
                mutate("duplicate-2", actor, original)
                observations.event("duplicate_component_limits", buildJsonObject {
                    put("after_process_restart", "NOT_RUN")
                    put("different_real_transport_request_ids", "NOT_RUN; direct service invocations only")
                })
            }
            "V-MISMATCH-BODY-SLICE" -> {
                val changed = frozen.getValue("per_command_variant_targets").jsonArray.single().jsonObject
                    .getValue("same_id_different_body").jsonObject
                check(changed.commerceText("field") == "counted_opening")
                val differentBody = LocalSalesCommands.cash(original.operationId,
                    originalDto.copy(opening = exactMoney(changed.commerceText("changed"))))
                mutate("mismatch-body", actor, differentBody)
                mutate("correct-retry", actor, original)
            }
            "V-MISMATCH-ACTOR-SLICE" -> {
                mutate("mismatch-actor", fixture.binding.commerceText("different_actor"), original)
                mutate("correct-retry", actor, original)
            }
        }
    }

    private fun requireFixtureApplied(result: MutationReply, label: String) {
        if (result !is MutationReply.Recorded || !result.committed) {
            throw CommerceV1WiringBlocked("commerce.fixture.$label.not_applied")
        }
    }

    private fun exactMoney(decimal: String): Money =
        Money(Currency.EGP, BigDecimal(decimal).movePointRight(2).longValueExact())

    private suspend fun openRequest(
        actor: String,
        drawerAlias: String,
        sessionAlias: String,
        sourceAlias: String,
        amount: String,
        evidenceAlias: String,
    ): LocalCashRequestV1.Open {
        val own = read("prerequisite-own-$actor-$sessionAlias", actor, LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash
            ?: throw CommerceV1WiringBlocked("commerce.binding.current_custodian_query")
        val drawers = read("prerequisite-drawers-$actor-$sessionAlias", actor, LocalCashQueryV1.Drawers()) as? LocalCashReadResultV1.Drawers
            ?: throw CommerceV1WiringBlocked("commerce.binding.current_drawer_query")
        val drawer = drawers.drawers.singleOrNull { it.id == fixture.id(drawerAlias) }
            ?: throw CommerceV1WiringBlocked("commerce.binding.drawer_missing")
        return LocalCashRequestV1.Open(fixture.id(sessionAlias), drawer.id, drawer.revision, own.assignmentRevision,
            BusinessDate(LocalDate.parse(fixture.metadata.commerceText("business_date"))), fixture.id(sourceAlias), exactMoney(amount),
            LocalCashEvidenceV1(fixture.id(evidenceAlias), UtcInstant.parseCanonical(fixture.metadata.commerceText("evidence_occurred_at"))))
    }

    private suspend fun mutate(label: String, actor: String, request: MutationRequest): MutationReply {
        val intent = observations.stage(label, actor, request)
        for (owner in listOf("A", "B")) read("$label-before-$owner", owner, LocalCashQueryV1.OwnCash)
        val before = observations.snapshot("$label.before.json", fixture.databasePath, fixture.scope)
        observations.event("dispatch", buildJsonObject {
            put("label", label); put("actor_alias", actor); put("operation_id", request.operationId.value)
            put("staged_intent", intent); put("before_snapshot", before)
            put("invocation_label", "SYNTHETIC-DIRECT-$label; NOT a P2pKit request ID")
        })
        val result = sales.execute(fixture.credentials(actor), request)
        val observedReply = when (result) {
            is MutationReply.Recorded -> buildJsonObject {
                put("type", "Recorded"); put("committed", result.committed); put("code", result.code)
                put("original_actor_id", result.originalActorId.value); put("operation_id", result.operationId.value)
                put("authority_epoch", result.authorityEpoch.value.toString()); put("sequence", result.sequence.value.toString())
                put("replayed", result.replayed); put("result", commerceV1JsonBytes(result.result.copyBytes()))
                put("result_sha256", commerceV1Hash(result.result.copyBytes()))
            }
            is MutationReply.Deferred -> buildJsonObject { put("type", "Deferred"); put("reason", result.reason.name) }
            is MutationReply.IdentityConflict -> buildJsonObject { put("type", "IdentityConflict") }
        }
        val replyFile = observations.save("$label.reply.json", observedReply)
        observations.event("mutation_reply_before_independent_read", buildJsonObject {
            put("label", label); put("reply_file", replyFile)
            put("not_acceptance_by_response_alone", true)
        })
        val after = observations.snapshot("$label.after.json", fixture.databasePath, fixture.scope)
        observations.event("mutation_observed", buildJsonObject {
            put("label", label); put("actor_alias", actor); put("operation_id", request.operationId.value)
            put("before_snapshot", before); put("after_snapshot", after); put("staged_intent", intent)
            put("reply_file", replyFile); put("reply", observedReply)
        })
        for (owner in listOf("A", "B")) read("$label-after-$owner", owner, LocalCashQueryV1.OwnCash)
        return result
    }

    private suspend fun read(label: String, actor: String, query: LocalCashQueryV1): LocalCashReadResultV1? {
        val result = sales.read(fixture.credentials(actor), LocalCashReadCodec.request(query))
        observations.event("protected_read_observed", buildJsonObject {
            put("label", label); put("actor_alias", actor)
            put("reply", when (result) {
                is ReadReply.Data -> buildJsonObject {
                    put("type", "Data"); put("branch_revision", result.branchRevision.value.toString())
                    put("payload", commerceV1JsonBytes(result.payload.copyBytes()))
                }
                is ReadReply.Unavailable -> buildJsonObject { put("type", "Unavailable"); put("reason", result.reason.name) }
            })
        })
        return if (result is ReadReply.Data) LocalCashReadCodec.decode(result.payload) else null
    }
}

/**
 * Versioned post-freeze expansion. One invocation is ONE JVM incarnation. Only the
 * external namespace supervisor can kill/reap it and start a successor. No fixture
 * close/reopen within this JVM is described as process restart.
 */
private object CommerceV2Runner {
    suspend fun execute(input: JsonObject, directory: Path) {
        val binding = input.getValue("binding").jsonObject
        val request = input.getValue("phase_request").jsonObject
        val job = binding.getValue("jobs").jsonArray.map { it.jsonObject }.single {
            it.commerceText("id") == request.commerceText("job_id")
        }
        val phaseIndex = request.getValue("phase_index").jsonPrimitive.int
        val phases = job.getValue("phases").jsonArray.map { it.jsonObject }
        check(phaseIndex in phases.indices && phaseIndex in 0..1)
        val phase = phases[phaseIndex]
        val databasePath = Path.of(request.commerceText("database_path")).toAbsolutePath().normalize()
        check(directory.fileName.toString() == "phase-$phaseIndex")
        check(databasePath == directory.parent.resolve("state/master.sqlite"))
        val prior = if (phaseIndex == 1) directory.parent.resolve("phase-0") else null
        check(request.commerceText("prior_observations") == (prior?.toString() ?: "NONE"))
        val family = job.commerceText("family")
        val familyBinding = binding.getValue("families").jsonObject.getValue(family).jsonObject
        val frozen = input.getValue("cases").jsonArray.map { it.jsonObject }.single {
            it.commerceText("case_id") == familyBinding.commerceText("case_id")
        }
        val observations = CommerceV1Observations(directory)
        val capsule = CommerceV2Capsule.read(request.getValue("capsule_fd").jsonPrimitive.int)
        val incarnation = CommerceV2Incarnation(
            DomainId(phase.commerceText("issuer_id")), DomainId(phase.commerceText("boot_id")),
            DomainId(phase.commerceText("connection_id")), phaseIndex == 1,
            if (job.commerceText("mode") == "DENIED_EXPLICIT") familyBinding.commerceText("original_actor") else null,
            capsule,
        )
        observations.event("incarnation_begin", buildJsonObject {
            put("case_id", frozen.commerceText("case_id")); put("job_id", job.commerceText("id"))
            put("mode", job.commerceText("mode")); put("phase_index", phaseIndex)
            put("pid", ProcessHandle.current().pid()); put("issuer_id", incarnation.issuerId.value)
            put("boot_id", incarnation.bootId.value); put("connection_id", incarnation.connectionId.value)
            put("network_namespace", Files.readSymbolicLink(Path.of("/proc/self/ns/net")).toString())
            put("capsule_material_exported", false); put("whole_case_pass_allowed", false)
        })
        val fixture = CommerceV1Fixture(familyBinding, binding.getValue("metadata").jsonObject,
            databasePath, observations, incarnation)
        var failed = false
        try {
            fixture.open()
            observations.snapshot("after-real-authentication.json", databasePath, fixture.scope)
            CommerceV2CashExecution(fixture, frozen, job, directory).execute(phaseIndex, prior)
            observations.event("phase_observed", buildJsonObject { put("verdict", "OBSERVATIONS_ONLY_PARTIAL") })
        } catch (error: Throwable) {
            failed = true
            observations.event("slice_error", buildJsonObject {
                put("category", if (error is CommerceV1WiringBlocked) "FIXTURE_OR_BINDING_BLOCKED" else "EXECUTOR_ERROR")
                put("error_type", error.javaClass.name)
                if (error is CommerceV1WiringBlocked) put("safe_code", error.code)
                put("frames", buildJsonArray { error.stackTrace.take(32).forEach { add(it.toString()) } })
                put("exception_messages_omitted", true)
            })
        } finally {
            try {
                fixture.close()
                if (Files.isRegularFile(databasePath)) observations.snapshot("final-after-close.json", databasePath, fixture.scope)
            } catch (_: CommerceV1RetirementRequired) {
                observations.event("runner_requires_process_reap")
                exitProcess(4)
            } catch (error: Throwable) {
                observations.event("close_or_final_read_error", buildJsonObject { put("error_type", error.javaClass.name) })
                exitProcess(5)
            }
        }
        if (failed) exitProcess(2)
    }
}

private class CommerceV2CashExecution(
    private val fixture: CommerceV1Fixture,
    private val frozen: JsonObject,
    private val job: JsonObject,
    private val directory: Path,
) {
    private val observations get() = fixture.observations
    private val sales get() = LocalSalesService(fixture.service.endpoint)
    private val action = frozen.getValue("actions").jsonArray.single().jsonObject
    private val inputs = action.getValue("inputs").jsonObject
    private val family = job.commerceText("family")
    private val mode = job.commerceText("mode")
    private val actor = action.commerceText("actor")

    suspend fun execute(phaseIndex: Int, prior: Path?) {
        if (phaseIndex == 1) {
            resume(requireNotNull(prior))
            return
        }
        setup(emptyDrawer = mode == "RACE_DISTINCT" && family == "CASH-02")
        val original = LocalSalesCommands.cash(OperationId(action.commerceText("operation_id")), openRequest(
            actor, inputs.commerceText("drawer"), fixture.binding.commerceText("session_for_original_action"),
            inputs.commerceText("source"), inputs.commerceText("counted_opening"), "evidence-open"))
        when (mode) {
            "DUPLICATE_RESTART" -> {
                val first = mutate("original", actor, original)
                if (first !is MutationReply.Recorded) return unmet("Original durable identity was not established")
                mutate("duplicate-before-restart", actor, original)
                reachCut("DUPLICATE_BEFORE_RESTART", original, responseSuppressed = false)
            }
            "LOST_REQUEST_RESTART" -> {
                stage("original", actor, original)
                observations.snapshot("original.before.json", fixture.databasePath, fixture.scope)
                observations.event("fault_armed_before_target", buildJsonObject {
                    put("fault", "DIRECT_ADAPTER_DROP_BEFORE_DELIVERY"); put("operation_id", original.operationId.value)
                    put("target_dispatched", false); put("physical_action_repeated", false)
                })
                observations.snapshot("original.after-suppression.json", fixture.databasePath, fixture.scope)
                observations.event("request_suppressed_before_delivery", buildJsonObject {
                    put("intent", "original.intent.json"); put("identity", "original.identity.json")
                    put("production_endpoint_called", false); put("real_transport_partition", "NOT_RUN")
                })
                reachCut("DROPPED_REQUEST_BEFORE_DELIVERY", original, responseSuppressed = false)
            }
            "LOST_RESPONSE_RESTART" -> loseOriginalResponse(original)
            "RACE_SAME_ID", "RACE_DISTINCT" -> race(original)
            "DENIED" -> denied(original)
            "DENIED_EXPLICIT" -> {
                observations.event("explicit_deny_fixture", buildJsonObject {
                    put("actor_alias", actor); put("action", "CASH_OPEN/PERFORM")
                    put("real_provisioning_with_synthetic_initial_authority", true)
                    put("policy_activation_or_revocation_variant", "NOT_RUN")
                })
                mutate("denied-explicit", actor,
                    MutationRequest(fixture.operation("denied-explicit"), original.command, original.payload))
            }
            "PRIVACY" -> {
                val result = mutate("original", actor, original)
                if (result !is MutationReply.Recorded) return unmet("Original identity/custody cut not established for private read")
                protectedProbe("wrong-owner-session", "B", LocalCashQueryV1.Session(fixture.id("SA")), fixture.credentials("B"))
                protectedProbe("no-read-grant-session", "M", LocalCashQueryV1.Session(fixture.id("SA")), fixture.credentials("M"))
                assertionExtra(original)
            }
            else -> throw CommerceV1WiringBlocked("commerce.binding.unknown_expansion_mode")
        }
    }

    private suspend fun setup(emptyDrawer: Boolean) {
        for (drawer in listOf("DA", "DB")) {
            requireApplied(mutate("fixture-configure-$drawer", "M", LocalSalesCommands.cash(
                fixture.operation("configure-$drawer"), LocalCashRequestV1.ConfigureDrawer(fixture.id(drawer), "SYN-$drawer", Currency.EGP))), drawer)
        }
        for (owner in listOf("A", "B")) {
            requireApplied(mutate("fixture-configure-$owner", "M", LocalSalesCommands.cash(
                fixture.operation("configure-$owner"), LocalCashRequestV1.ConfigureCustodian(fixture.id(owner)))), owner)
        }
        if (family == "CASH-02" && !emptyDrawer) {
            val opening = frozen.getValue("fixture").jsonObject.getValue("open_sessions").jsonArray.single().jsonObject
            requireApplied(mutate("fixture-open-A", "A", LocalSalesCommands.cash(fixture.operation("fixture-open-A"),
                openRequest("A", "DA", "SA", "safe-float-A-fixture", opening.commerceText("float"), "evidence-fixture-open-A"))), "open-A")
        }
        observations.event("fixture_ready", buildJsonObject {
            put("empty_drawer_specific_variant", emptyDrawer)
            put("arbitrary_business_rows_seeded", false)
        })
    }

    private fun requireApplied(reply: MutationReply, label: String) {
        if (reply !is MutationReply.Recorded || !reply.committed) throw CommerceV1WiringBlocked("commerce.fixture.$label.not_applied")
    }

    private fun identity(actorAlias: String, request: MutationRequest): OperationIdentity = OperationIdentity(
        fixture.scope.branchId, request.operationId, fixture.id(actorAlias), 1,
        semanticDigest(fixture.scope, fixture.id(actorAlias), request.command, LocalCashCommandCodec.normalize(request.payload)).value,
    )

    private fun identityJson(value: OperationIdentity): JsonObject = buildJsonObject {
        put("branch_id", value.branchId.value); put("operation_id", value.operationId.value)
        put("original_actor_id", value.originalActorId.value); put("semantic_digest_version", value.semanticDigestVersion)
        put("semantic_sha256", value.semanticSha256)
        put("binding_only", "Production canonical codec/digest locate the original operation; not a business oracle")
    }

    private fun stage(label: String, actorAlias: String, request: MutationRequest): String {
        val name = observations.stage(label, actorAlias, request)
        observations.save("$label.identity.json", identityJson(identity(actorAlias, request)))
        return name
    }

    private suspend fun mutate(
        label: String,
        actorAlias: String,
        request: MutationRequest,
        credentials: LocalGatewayCredentials = fixture.credentials(actorAlias),
    ): MutationReply {
        val intent = stage(label, actorAlias, request)
        val before = observations.snapshot("$label.before.json", fixture.databasePath, fixture.scope)
        observations.event("dispatch", buildJsonObject {
            put("label", label); put("actor_alias", actorAlias); put("operation_id", request.operationId.value)
            put("staged_intent", intent); put("before_snapshot", before)
            put("invocation_label", "SYNTHETIC-DIRECT-$label; NOT a P2pKit request ID")
        })
        val result = sales.execute(credentials, request)
        val reply = commerceV2Reply(result)
        val replyFile = observations.save("$label.reply.json", reply)
        observations.event("server_reply_before_independent_read", buildJsonObject {
            put("label", label); put("reply_file", replyFile)
            put("initiating_client_response_delivered", true)
            put("prearmed_response_suppression", false)
        })
        val after = observations.snapshot("$label.after.json", fixture.databasePath, fixture.scope)
        observations.event("mutation_observed", buildJsonObject {
            put("label", label); put("actor_alias", actorAlias); put("operation_id", request.operationId.value)
            put("before_snapshot", before); put("after_snapshot", after); put("staged_intent", intent)
            put("reply_file", replyFile); put("reply", reply); put("initiating_client_response_delivered", true)
        })
        for (owner in listOf("A", "B")) read("$label-after-$owner", owner, LocalCashQueryV1.OwnCash)
        return result
    }

    /** The initiator never calls the observing continuation and never receives its server reply. */
    private suspend fun loseOriginalResponse(original: MutationRequest): Unit = coroutineScope {
        val label = "original"
        val intent = stage(label, actor, original)
        val before = observations.snapshot("$label.before.json", fixture.databasePath, fixture.scope)
        val adapter = CommerceV2LostResponseAdapter()
        val credentials = fixture.credentials(actor)
        val receivedByInitiator = AtomicInteger(0)
        observations.event("fault_armed_before_target", buildJsonObject {
            put("fault", "DIRECT_ADAPTER_DISCARD_RESPONSE_AFTER_DURABLE_RESULT")
            put("operation_id", original.operationId.value)
            put("adapter_protocol", "commerce-one-shot-response-loss/v1")
            put("not_a_transport_fault", true)
        })
        // UNDISPATCHED returns to this observer only after submit's actual suspension.
        // No server is started until that distinct initiating continuation is waiting.
        val initiator = async(CoroutineName("commerce-initiating-client"), start = CoroutineStart.UNDISPATCHED) {
            val reply = adapter.submit(credentials, original)
            receivedByInitiator.incrementAndGet()
            observations.event("initiator_response_received", buildJsonObject {
                put("label", label); put("operation_id", original.operationId.value)
                put("reply", commerceV2Reply(reply))
            })
            reply
        }
        observations.event("initiator_waiting_before_dispatch", buildJsonObject {
            put("label", label); put("operation_id", original.operationId.value)
            put("state", adapter.observeInitiator(initiator, null, receivedByInitiator.get()))
        })
        check(adapter.initiatorIsPending(initiator, receivedByInitiator.get()))
        val server = launch(CoroutineName("commerce-server-response-observer")) {
            adapter.serve { suppliedCredentials, submittedRequest ->
                observations.event("dispatch", buildJsonObject {
                    put("label", label); put("actor_alias", actor); put("operation_id", submittedRequest.operationId.value)
                    put("staged_intent", intent); put("before_snapshot", before)
                    put("invocation_label", "SYNTHETIC-DIRECT-$label; NOT a P2pKit request ID")
                })
                sales.execute(suppliedCredentials, submittedRequest)
            }
        }
        // This is a separate server-only observation channel, not the client return path.
        val serverResult = adapter.observeServerReply()
        server.join()
        val reply = commerceV2Reply(serverResult)
        val replyFile = observations.save("$label.reply.json", reply)
        observations.event("server_reply_before_independent_read", buildJsonObject {
            put("label", label); put("reply_file", replyFile)
            put("initiating_client_response_delivered", receivedByInitiator.get() != 0)
            put("prearmed_response_suppression", true)
            put("state", adapter.observeInitiator(initiator, server, receivedByInitiator.get()))
        })
        val after = observations.snapshot("$label.after.json", fixture.databasePath, fixture.scope)
        observations.event("mutation_observed", buildJsonObject {
            put("label", label); put("actor_alias", actor); put("operation_id", original.operationId.value)
            put("before_snapshot", before); put("after_snapshot", after); put("staged_intent", intent)
            put("reply_file", replyFile); put("reply", reply)
            put("initiating_client_response_delivered", receivedByInitiator.get() != 0)
            put("reply_observation_source", "SEPARATE_SERVER_CHANNEL_NOT_INITIATOR")
        })
        for (owner in listOf("A", "B")) read("$label-after-$owner", owner, LocalCashQueryV1.OwnCash)
        if (serverResult !is MutationReply.Recorded) {
            unmet("No durable original identity at the pre-armed response-loss cut")
            initiator.cancelAndJoin()
            return@coroutineScope
        }
        val state = adapter.observeInitiator(initiator, server, receivedByInitiator.get())
        observations.save("$label.initiator-state.json", state)
        observations.event("initiator_pending_at_response_loss_cut", buildJsonObject {
            put("label", label); put("operation_id", original.operationId.value)
            put("state_file", "$label.initiator-state.json"); put("state", state)
        })
        check(adapter.initiatorIsPending(initiator, receivedByInitiator.get()))
        check(server.isCompleted && !server.isCancelled)
        // Neither successful observer return nor client cancellation happens before the cut.
        // The external supervisor must actually SIGKILL/reap this JVM with the client pending.
        reachCut("DROPPED_RESPONSE_AFTER_DURABLE_RESULT", original, responseSuppressed = true, initiatorState = state)
    }

    private suspend fun resume(prior: Path) {
        check(mode in setOf("DUPLICATE_RESTART", "LOST_REQUEST_RESTART", "LOST_RESPONSE_RESTART"))
        val staged = Json.parseToJsonElement(Files.readString(prior.resolve("original.intent.json"))).jsonObject
        check(staged.commerceText("operation_id") == action.commerceText("operation_id") && staged.commerceText("actor_alias") == actor)
        val bytes = Base64.getDecoder().decode(staged.commerceText("payload_base64"))
        check(commerceV1Hash(bytes) == staged.commerceText("payload_sha256"))
        check(bytes.decodeToString(throwOnInvalidSequence = true) == staged.commerceText("payload_utf8"))
        val original = MutationRequest(OperationId(staged.commerceText("operation_id")),
            CommandKey(staged.commerceText("command"), staged.getValue("command_version").jsonPrimitive.int), StoredBytes(bytes))
        check(original.command == LocalSalesStoreProfile.CASH_COMMAND)
        val originalIdentity = identity(actor, original)
        check(identityJson(originalIdentity) == Json.parseToJsonElement(Files.readString(prior.resolve("original.identity.json"))))
        observations.event("original_intent_reloaded", buildJsonObject {
            put("operation_id", original.operationId.value); put("payload_sha256", commerceV1Hash(original.payload.copyBytes()))
            put("original_actor_alias", actor); put("expected_revisions_refreshed", false)
            put("prior_relative_intent", "../phase-0/original.intent.json"); put("physical_action_repeated", false)
        })
        val before = observations.snapshot("recovery-lookup.before.json", fixture.databasePath, fixture.scope)
        val lookup = sales.lookup(fixture.credentials(actor), originalIdentity)
        val replyFile = observations.save("recovery-lookup.reply.json", commerceV2Lookup(lookup))
        val after = observations.snapshot("recovery-lookup.after.json", fixture.databasePath, fixture.scope)
        observations.event("lookup_observed", buildJsonObject {
            put("label", "recovery-lookup"); put("identity", identityJson(originalIdentity))
            put("reply", commerceV2Lookup(lookup)); put("reply_file", replyFile)
            put("before_snapshot", before); put("after_snapshot", after)
            put("absence_qualification", "Synthetic complete-history/writer port only; NOT physical-payment outcome proof")
        })
        if (mode == "LOST_REQUEST_RESTART") {
            if (lookup !is LookupReply.AbsentAtCurrentWriter) return unmet("Current writer did not supply the required initial absence observation")
            val result = mutate("same-id-resend", actor, original)
            if (result !is MutationReply.Recorded) return unmet("Lost-request same-ID resend did not establish a durable identity")
            mutate("same-id-retry", actor, original)
        } else {
            if (lookup !is LookupReply.Found) return unmet("Original durable result not recovered by actual lookup")
            mutate("after-restart-retry", actor, original)
        }
        assertionExtra(original)
    }

    private suspend fun assertionExtra(original: MutationRequest) {
        val request = if (family == "CASH-01") MutationRequest(fixture.operation("assertion-extra-open"), original.command, original.payload)
        else LocalSalesCommands.cash(fixture.operation("assertion-extra-open"), openRequest(
            "A", "DB", "SA-DB-ATTEMPT", "safe-float-A-DB", inputs.commerceText("counted_opening"), "evidence-A-DB"))
        mutate("assertion-extra-open", "A", request)
    }

    private suspend fun race(original: MutationRequest): Unit = coroutineScope {
        val competitorActor: String
        val competitor: MutationRequest
        if (mode == "RACE_SAME_ID") {
            competitorActor = actor
            competitor = original
        } else if (family == "CASH-02") {
            competitorActor = "A"
            competitor = LocalSalesCommands.cash(fixture.operation("race-distinct-A"), openRequest(
                "A", "DA", "SA", "safe-float-A-fixture", inputs.commerceText("counted_opening"), "evidence-fixture-open-A"))
        } else {
            competitorActor = actor
            competitor = MutationRequest(fixture.operation("race-distinct-other"), original.command, original.payload)
        }
        val firstDto = LocalCashCommandCodec.decode(original.payload) as LocalCashRequestV1.Open
        val secondDto = LocalCashCommandCodec.decode(competitor.payload) as LocalCashRequestV1.Open
        check(firstDto.expectedDrawerRevision == secondDto.expectedDrawerRevision)
        val requests = listOf(actor to original, competitorActor to competitor)
        val intents = requests.mapIndexed { index, (owner, request) -> stage("race-$index", owner, request) }
        val before = observations.snapshot("race.before.json", fixture.databasePath, fixture.scope)
        val barrier = CyclicBarrier(3)
        observations.event("race_barrier_armed", buildJsonObject {
            put("participants", 2); put("mode", mode); put("schedule", "single CyclicBarrier(3), no sleeps or retries")
            put("expected_drawer_revision", firstDto.expectedDrawerRevision.value.toString())
            put("original_operation_id_preserved", original.operationId.value)
            put("physical_notes_or_transport", "NOT_RUN; counted-input durable transition component only")
        })
        val pending = requests.mapIndexed { index, (owner, request) ->
            async(Dispatchers.Default) {
                observations.event("race_participant_ready", buildJsonObject {
                    put("index", index); put("operation_id", request.operationId.value)
                })
                barrier.await(20, TimeUnit.SECONDS)
                observations.event("race_dispatch_enter", buildJsonObject {
                    put("index", index); put("monotonic_nanos", System.nanoTime().toString())
                })
                val result = sales.execute(fixture.credentials(owner), request)
                val observed = commerceV2Reply(result)
                val replyFile = observations.save("race-$index.reply.json", observed)
                observations.event("race_server_reply", buildJsonObject { put("index", index); put("reply_file", replyFile) })
                observed
            }
        }
        barrier.await(20, TimeUnit.SECONDS)
        observations.event("race_barrier_released", buildJsonObject { put("monotonic_nanos", System.nanoTime().toString()) })
        val replies = pending.awaitAll()
        val after = observations.snapshot("race.after.json", fixture.databasePath, fixture.scope)
        observations.event("race_observed", buildJsonObject {
            put("mode", mode); put("before_snapshot", before); put("after_snapshot", after)
            put("requests", buildJsonArray {
                requests.forEachIndexed { index, (owner, request) -> add(buildJsonObject {
                    put("index", index); put("actor_alias", owner); put("operation_id", request.operationId.value)
                    put("intent", intents[index]); put("reply_file", "race-$index.reply.json"); put("reply", replies[index])
                }) }
            })
        })
        for (owner in listOf("A", "B")) read("race-after-$owner", owner, LocalCashQueryV1.OwnCash)
    }

    private suspend fun denied(original: MutationRequest) {
        val unissuedBytes = ByteArray(52).also(SecureRandom()::nextBytes)
        val bearer = try { OpaqueCredential(unissuedBytes) } finally { unissuedBytes.fill(0) }
        try {
            val credentials = listOf(
                "denied-unadmitted" to fixture.unissuedCredentials(false, bearer),
                "denied-device-only" to fixture.unissuedCredentials(true, bearer),
                "denied-no-grant" to fixture.credentials("M"),
                "denied-foreign-branch" to fixture.credentialsAt(actor, "SYN-FOREIGN-BRANCH-CONNECTION"),
                "denied-foreign-company" to fixture.credentialsAt(actor, "SYN-FOREIGN-COMPANY-CONNECTION"),
                "denied-foreign-organization" to fixture.credentialsAt(actor, "SYN-FOREIGN-ORGANIZATION-CONNECTION"),
            )
            observations.event("denial_binding_limits", buildJsonObject {
                put("missing_bearer_wire_case", "NOT_RUN: production in-process credentials require a bearer; an unissued random bearer is supplied")
                put("foreign_peer_component", "Different synthetic peer scope and connection; does not isolate a physical admitted-foreign-device route")
                put("foreign_business_relation_rows", "NOT_RUN: no fabricated foreign business fixture")
            })
            for ((label, credential) in credentials) {
                val intendedActor = if (label == "denied-no-grant") "M" else actor
                mutate(label, intendedActor, MutationRequest(fixture.operation(label), original.command, original.payload), credential)
                protectedProbe("$label-own", intendedActor, LocalCashQueryV1.OwnCash, credential)
                protectedProbe("$label-drawers", intendedActor, LocalCashQueryV1.Drawers(), credential)
            }
        } finally { bearer.destroy() }
    }

    private suspend fun openRequest(actor: String, drawerAlias: String, sessionAlias: String,
        sourceAlias: String, amount: String, evidenceAlias: String): LocalCashRequestV1.Open {
        val own = read("prerequisite-own-$actor-$sessionAlias", actor, LocalCashQueryV1.OwnCash) as? LocalCashReadResultV1.OwnCash
            ?: throw CommerceV1WiringBlocked("commerce.binding.current_custodian_query")
        val drawers = read("prerequisite-drawers-$actor-$sessionAlias", actor, LocalCashQueryV1.Drawers()) as? LocalCashReadResultV1.Drawers
            ?: throw CommerceV1WiringBlocked("commerce.binding.current_drawer_query")
        val drawer = drawers.drawers.singleOrNull { it.id == fixture.id(drawerAlias) }
            ?: throw CommerceV1WiringBlocked("commerce.binding.drawer_missing")
        return LocalCashRequestV1.Open(fixture.id(sessionAlias), drawer.id, drawer.revision, own.assignmentRevision,
            BusinessDate(LocalDate.parse(fixture.metadata.commerceText("business_date"))), fixture.id(sourceAlias),
            Money(Currency.EGP, BigDecimal(amount).movePointRight(2).longValueExact()),
            LocalCashEvidenceV1(fixture.id(evidenceAlias), UtcInstant.parseCanonical(fixture.metadata.commerceText("evidence_occurred_at"))))
    }

    private suspend fun read(label: String, actor: String, query: LocalCashQueryV1): LocalCashReadResultV1? {
        val result = sales.read(fixture.credentials(actor), LocalCashReadCodec.request(query))
        observations.event("protected_read_observed", buildJsonObject {
            put("label", label); put("actor_alias", actor); put("reply", commerceV2Read(result))
        })
        return if (result is ReadReply.Data) LocalCashReadCodec.decode(result.payload) else null
    }

    private suspend fun protectedProbe(label: String, actor: String, query: LocalCashQueryV1, credentials: LocalGatewayCredentials) {
        val request = LocalCashReadCodec.request(query)
        val before = observations.snapshot("$label.before.json", fixture.databasePath, fixture.scope)
        val result = sales.read(credentials, request)
        val reply = commerceV2Read(result)
        val replyFile = observations.save("$label.read-reply.json", reply)
        val after = observations.snapshot("$label.after.json", fixture.databasePath, fixture.scope)
        val decodedKind = if (result is ReadReply.Data) {
            try {
                if (LocalCashReadCodec.decode(result.payload) == LocalCashReadResultV1.NotAvailable) "NOT_AVAILABLE" else "OTHER_DATA"
            } catch (_: Exception) { "UNDECODABLE_DATA" }
        } else "NO_DATA"
        observations.event("protected_probe_observed", buildJsonObject {
            put("label", label); put("actor_alias", actor); put("query", commerceV1JsonBytes(request.payload.copyBytes()))
            put("reply", reply); put("reply_file", replyFile); put("before_snapshot", before); put("after_snapshot", after)
            put("decoded_kind", decodedKind)
        })
    }

    private fun unmet(reason: String) {
        observations.event("remaining_slice_precondition_unmet", buildJsonObject {
            put("safe_reason", reason); put("remaining_variant", "BLOCKED_NOT_EXERCISED")
        })
    }

    private suspend fun reachCut(
        cut: String, original: MutationRequest, responseSuppressed: Boolean, initiatorState: JsonObject? = null,
    ): Nothing {
        observations.save("cut-ready.json", buildJsonObject {
            put("job_id", job.commerceText("id")); put("cut", cut); put("pid", ProcessHandle.current().pid())
            put("original_operation_id", original.operationId.value); put("intent", "original.intent.json")
            put("response_suppressed", responseSuppressed); put("waiting_for_external_SIGKILL", true)
            put("not_graceful_close_or_same_process_reopen", true)
            if (responseSuppressed) put("initiator_state", requireNotNull(initiatorState))
        })
        // Publish readiness ONLY after the record's file+directory fsync. The supervisor
        // never treats a half-written JSON file as the cut and never repeats a command.
        Files.createDirectory(directory.resolve("cut-ready"))
        awaitCancellation()
    }
}

/**
 * One-shot executor adapter, not a replacement business endpoint. The actual initiating
 * request crosses a request channel to the real endpoint. Its CancellableContinuation is
 * deliberately never resumed: only the distinct server-observation channel gets the reply.
 * No timer, post-success cancellation or telemetry flag implements this response loss.
 */
private class CommerceV2LostResponseAdapter {
    private val submitted = CompletableDeferred<Pair<LocalGatewayCredentials, MutationRequest>>()
    private val serverObservation = CompletableDeferred<MutationReply>()
    private val waiting = AtomicReference<CancellableContinuation<MutationReply>?>(null)

    suspend fun submit(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply =
        suspendCancellableCoroutine { continuation ->
            check(waiting.compareAndSet(null, continuation))
            check(submitted.complete(credentials to request))
        }

    suspend fun serve(endpoint: suspend (LocalGatewayCredentials, MutationRequest) -> MutationReply) {
        val (credentials, request) = submitted.await()
        val reply = endpoint(credentials, request)
        check(serverObservation.complete(reply))
        // Intentionally no waiting.get().resume(reply): client delivery is withheld.
    }

    suspend fun observeServerReply(): MutationReply = serverObservation.await()

    fun initiatorIsPending(initiator: Job, receivedResults: Int): Boolean {
        val continuation = waiting.get() ?: return false
        return continuation.isActive && !continuation.isCompleted && !continuation.isCancelled &&
            initiator.isActive && !initiator.isCompleted && !initiator.isCancelled && receivedResults == 0
    }

    fun observeInitiator(initiator: Job, server: Job?, receivedResults: Int): JsonObject {
        val continuation = waiting.get()
        return buildJsonObject {
            put("adapter_protocol", "commerce-one-shot-response-loss/v1")
            put("initiator_continuation_present", continuation != null)
            put("initiator_continuation_active", continuation?.isActive == true)
            put("initiator_continuation_completed", continuation?.isCompleted == true)
            put("initiator_continuation_cancelled", continuation?.isCancelled == true)
            put("initiator_job_active", initiator.isActive)
            put("initiator_job_completed", initiator.isCompleted)
            put("initiator_job_cancelled", initiator.isCancelled)
            put("initiator_received_results", receivedResults)
            put("request_channel_completed", submitted.isCompleted)
            put("request_channel_cancelled", submitted.isCancelled)
            put("server_observation_channel_completed", serverObservation.isCompleted)
            put("server_observation_channel_cancelled", serverObservation.isCancelled)
            put("server_coroutine_started", server != null)
            put("server_coroutine_completed", server?.isCompleted == true)
            put("server_coroutine_cancelled", server?.isCancelled == true)
            put("distinct_client_and_server_coroutines", server != null && initiator !== server)
        }
    }
}
