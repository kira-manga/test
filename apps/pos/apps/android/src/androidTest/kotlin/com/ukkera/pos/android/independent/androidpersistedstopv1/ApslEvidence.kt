package com.ukkera.pos.android.independent.androidpersistedstopv1

import android.app.Application
import android.util.AtomicFile
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only observations for one selected E3-006/APSL v2 case. It retains synthetic IDs,
 * digests, actual lifecycle observations and explicit unbound obligations only. No password,
 * token, key, private path or protected row is written. The file also marks the fresh
 * isolated installation used by one selected case.
 */
internal class ApslEvidence private constructor(
    private val file: AtomicFile,
    private val document: JSONObject,
) : NativeLocalFixtureEvidence {
    override fun event(step: String, facts: JSONObject) {
        document.getJSONArray("events").put(JSONObject().put("event", step).put("facts", facts))
        persist()
    }

    override fun bind(symbol: String, value: Any?) {
        document.getJSONObject("bindings").put(symbol, value ?: JSONObject.NULL)
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
        private const val FILE_NAME = "apsl-v2-selected-case.json"

        fun claim(application: Application, case: String, protection: String): ApslEvidence {
            val plain = File(application.noBackupFilesDir, FILE_NAME)
            check(!plain.exists() && !File("${plain.path}.bak").exists() && !File("${plain.path}.new").exists()) {
                "APSL requires a fresh isolated localTesting installation; an earlier selected case is retained"
            }
            val document = JSONObject()
                .put("design", "foundation-android-persisted-stop-lifecycle-v2")
                .put("designCommit", "5d8d96c4ee066ad9838d72b2af0cc9eb493193a8")
                .put("selectedCase", case)
                .put("fixture", when (case) {
                    "STOP_RESUME" -> "APS-RUN-LCR (setup-only segment; LCR-R1 arithmetic unbound)"
                    "LOCK_BACKGROUND_REOPEN" -> "APS-PENDING-LCR/APS-STOP-ACK (setup-only lifecycle segment)"
                    "STOP_CLOSE_ACCOUNTING" -> "APS-STOP-ACK/APS-STOP-PRECLOSE (service retirement segment)"
                    "A1_LOGOUT_PENDING" -> "APS-LOCK (A1/D1 pending CASH segment; ALCR-UNKNOWN arithmetic unbound)"
                    "OWNER_CLOSE_ACCOUNTING" -> "APS-STOP-PRECLOSE (direct owner close; launch STOP unbound)"
                    else -> "UNKNOWN"
                })
                .put("status", "STARTED_NOT_RUN")
                .put("recordedAtEpochMillis", System.currentTimeMillis())
                .put("keyProtectionRequested", protection)
                .put("centralAvailable", false)
                .put("standbyConfigured", false)
                .put("syntheticOnly", true)
                .put("events", JSONArray())
                .put("bindings", JSONObject())
                .put("unbound", JSONArray())
                .put("runtime", "INSTRUMENTATION_ATTEMPT")
            val evidence = ApslEvidence(AtomicFile(plain), document)
            evidence.persist()
            return evidence
        }
    }
}
