package com.ukkera.pos.android.independent.nativeoriginalackv4

import android.app.Application
import android.util.AtomicFile
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only evidence for one selected E3-004 native-ACK segment. It records generated
 * non-secret identities, digests, actual outcomes and explicit unbound obligations. Credentials,
 * tokens, keys and protected rows never enter this file. The retained marker also ensures a
 * second selected segment cannot reuse the same app-private installation and call it fresh.
 */
internal class NativeAckEvidence private constructor(
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
        private const val FILE_NAME = "native-ack-v4-selected-segment.json"

        fun claim(application: Application, segment: String, protection: String): NativeAckEvidence {
            val plain = File(application.noBackupFilesDir, FILE_NAME)
            check(!plain.exists() && !File("${plain.path}.bak").exists() && !File("${plain.path}.new").exists()) {
                "E3-004 native ACK requires a fresh isolated localTesting installation"
            }
            val document = JSONObject()
                .put("fixture", "E3-004-NATIVE-ORIGINAL-ACK-V4-C1")
                .put("selectedSegment", segment)
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
            val evidence = NativeAckEvidence(AtomicFile(plain), document)
            evidence.persist()
            return evidence
        }
    }
}
