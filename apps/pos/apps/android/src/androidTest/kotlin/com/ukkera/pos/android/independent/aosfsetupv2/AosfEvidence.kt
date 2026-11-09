package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only fixture evidence. It contains generated non-secret identities, digests, status
 * names and unbound obligations only. Credentials, passwords, tokens and private rows never
 * enter this file. The retained file is also the fresh-installation marker: a second selected
 * case cannot reuse an app-private installation and call it a new AOSF fixture.
 */
internal class AosfEvidence private constructor(
    private val application: Application,
    private val file: AtomicFile,
    private val document: JSONObject,
) {
    fun event(name: String, facts: JSONObject = JSONObject()) {
        document.getJSONArray("events").put(JSONObject().put("event", name).put("facts", facts))
        persist()
    }

    fun bind(name: String, value: Any?) {
        document.getJSONObject("bindings").put(name, value ?: JSONObject.NULL)
        persist()
    }

    fun unbound(id: String, reason: String) {
        document.getJSONArray("unbound").put(JSONObject().put("id", id).put("reason", reason))
        persist()
    }

    fun status(value: String) {
        document.put("status", value)
        document.put("runtime", value)
        persist()
    }

    fun retainUncertainOriginal(operationId: String, actorId: String, role: String,
        branchId: String, installationId: String, ownerAcquisitionId: String) {
        document.put("uncertainOriginal", JSONObject()
            .put("kind", "SYNTHETIC_SEPARATE_EXTERNAL_ACQUISITION_ATTESTATION")
            .put("operationId", operationId).put("actorId", actorId).put("actorRole", role)
            .put("branchId", branchId).put("installationId", installationId)
            .put("capturedBeforeOutcomeUnknown", true).put("outcome", "UNKNOWN_UNRESOLVED")
            .put("nativeOwnerAcquisitionId", ownerAcquisitionId)
            .put("autoResume", "FORBIDDEN").put("autoRetry", "FORBIDDEN")
            .put("reanchor", "FORBIDDEN").put("newIdentity", "FORBIDDEN"))
        persist()
    }

    fun uncertainOriginal(): JSONObject = JSONObject(document.getJSONObject("uncertainOriginal").toString())

    fun fail(failure: Throwable) {
        document.put("status", "FAILED")
        document.put("runtime", "INSTRUMENTATION_FAILED")
        document.put("failure", JSONObject().put("type", failure.javaClass.name)
            .put("message", bounded(failure.message)))
        runCatching { persist() }
    }

    fun persist() {
        val bytes = document.toString().encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            try { file.failWrite(stream) } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun bounded(value: String?): String =
        (value ?: "").replace(Regex("[0-9a-fA-F]{24,}"), "[REDACTED]").take(512)

    companion object {
        private const val FILE_NAME = "aosf-v2-selected-installation.json"

        fun claim(application: Application, case: String, fixture: AosfFixtureKind,
            protection: String): AosfEvidence {
            val plain = File(application.noBackupFilesDir, FILE_NAME)
            check(!plain.exists() && !File("${plain.path}.bak").exists() && !File("${plain.path}.new").exists()) {
                "AOSF requires a fresh isolated localTesting installation; an earlier selected case is retained"
            }
            val document = JSONObject()
                .put("fixture", fixture.id).put("selectedCase", case)
                .put("status", "STARTED_NOT_RUN")
                .put("recordedAtEpochMillis", System.currentTimeMillis())
                .put("keyProtectionRequested", protection)
                .put("currency", AosfFixturePlan.currency)
                .put("businessTimezone", AosfFixturePlan.businessTimezone)
                .put("setupDefinitionKey", AosfFixturePlan.setupDefinitionKey)
                .put("setupDefinitionVersion", AosfFixturePlan.setupDefinitionVersion)
                .put("centralAvailable", false).put("standbyConfigured", false).put("syntheticOnly", true)
                .put("events", JSONArray()).put("bindings", JSONObject()).put("unbound", JSONArray())
                .put("runtime", "INSTRUMENTATION_ATTEMPT")
            val evidence = AosfEvidence(application, AtomicFile(plain), document)
            evidence.persist()
            return evidence
        }
    }
}
