package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.gateway.LocalClientDeviceIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionTokens
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/**
 * Unchanged application-authentication-attachment v1. Native/issued slots are encoded as
 * syntax-only supporting DATA; no actual native original, issued token or activated policy
 * is supplied. Numeric DTO carriers below are never a throttle, lifetime or UI-input oracle.
 */
internal object AppAuthenticationFrozen {
    private const val directory = "qa/pos/designs/application-authentication-attachment"
    private val expectedHashes = mapOf(
        "README.md" to "d02b5df1e12232150720d24204b8ae7a35dddaaab0095df8c1ca109a68a260af",
        "cases.md" to "d2fbcd2fc8d8be73b4170784866b9bb1284d5b16911596e5d7a8e1afb70e6e32",
        "fixtures.json" to "d12679bc49857af16054608fa6673f3828d8b0c20afd97beb6e2a6e4360aaa49",
    )
    private val location = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve(directory) }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen application authentication inputs are unavailable")

    val fixtures: JsonObject = run {
        for ((name, expected) in expectedHashes) {
            val bytes = Files.readAllBytes(location.resolve(name))
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Frozen application authentication input changed: $name")
        }
        Json.parseToJsonElement(Files.readString(location.resolve("fixtures.json"))).jsonObject.also {
            assertEquals("application-authentication-attachment", it.text("design"))
            assertEquals("1", it.text("version"))
        }.getValue("fixtures").jsonObject
    }

    private val base = fixtures.getValue("AA-F-BASE").jsonObject
    private val auth = fixtures.getValue("AA-F-AUTH").jsonObject
    private val identities = base.getValue("identities").jsonObject
    val scope = BranchScope(id("ORG_ONE"), id("COMPANY_CLOTHING"), id("BRANCH_A"))
    val actor: DomainId = id("EMP_A")
    val otherActor: DomainId = id("EMP_OTHER")
    val branchB: DomainId = id("BRANCH_B")
    val login = LocalLoginIdentifier(base.getValue("accounts").jsonObject.getValue("EMP_A").jsonObject.text("login_identifier"))
    val device: DomainId = slotId("DEVICE_D1")
    val otherDevice: DomainId = slotId("DEVICE_D2")
    val owner = LocalClientDeviceIdentity(scope, device)
    val connectionId: DomainId = slotId("CORR_A1")
    val successorConnectionId: DomainId = slotId("A2")
    val firstSessionId: DomainId = slotId("S1")
    val secondSessionId: DomainId = slotId("S2")

    /** Canonical conversion of a named frozen slot, not a native factory or authority. */
    fun slotId(slot: String): DomainId = DomainId(UUID.nameUUIDFromBytes(("APP-AUTH-v1:" + slot).encodeToByteArray()).toString())
    fun id(alias: String): DomainId = DomainId(identities.text(alias))
    fun exchange(alias: String): DomainId = DomainId(auth.getValue("exchange_ids").jsonObject.text(alias))
    fun password(slot: String = "PASSWORD_A"): CharArray {
        require(slot in auth.getValue("runtime_secret_slots").jsonObject)
        return ("supporting-runtime-slot:" + slot).toCharArray()
    }

    /**
     * P_SINGLE supplies the one-session relation; empty grants, revision zero, generation
     * carriers, boot UUID, deadline numbers and non-exercised throttle fields are explicit
     * DTO DATA. No service activates/enforces them and AA-GAP-02 remains open.
     */
    fun view(
        sessionId: DomainId = firstSessionId,
        actorId: DomainId = actor,
        deviceId: DomainId = device,
        branchId: DomainId = scope.branchId,
        generation: Long = 1,
        accessDeadline: Long = 100,
        workDeadline: Long = 100,
    ): LocalSessionView {
        val selected = scope.copy(branchId = branchId)
        val policy = ActivatedLocalPolicy(selected, actorId, com.ukkera.pos.types.Revision(0), emptyList(),
            LocalSessionPolicy(1), ThrottlePolicy(0, 1_000, 1_000, 1_000))
        val boot = slotId("W1:display-clock-carrier")
        return LocalSessionView(actorId, selected, deviceId, sessionId, SessionGeneration(generation), login,
            policy, MonotonicMoment(boot, accessDeadline), MonotonicMoment(boot, workDeadline))
    }

    fun successorView(): LocalSessionView = view(sessionId = secondSessionId, generation = 2)

    /** Syntax-only ULA1/ULR1 carrier required by the storage DTO, never a verified bearer. */
    fun tokenBytes(view: LocalSessionView, refresh: Boolean): ByteArray {
        val result = ByteArray(52)
        (if (refresh) "ULR1" else "ULA1").encodeToByteArray().copyInto(result)
        val locator = view.sessionId.value.replace("-", "")
        repeat(16) { index -> result[4 + index] = locator.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        val slot = (if (view.sessionId == secondSessionId) "S2" else "S1") + if (refresh) "_REFRESH" else "_ACCESS"
        val marker = slot.encodeToByteArray()
        repeat(32) { index -> result[20 + index] = marker[index % marker.size] }
        marker.fill(0)
        return result
    }

    fun tokens(view: LocalSessionView): LocalSessionTokens {
        val access = tokenBytes(view, refresh = false)
        val refresh = tokenBytes(view, refresh = true)
        try { return LocalSessionTokens(view, OpaqueCredential(access), OpaqueCredential(refresh)) }
        finally { access.fill(0); refresh.fill(0) }
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
