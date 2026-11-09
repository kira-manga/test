package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalDevicePairingProfileV1
import com.ukkera.pos.security.LocalDevicePairingTargetPhase
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Small original-intent metadata, never native custody or completion evidence.
 * Canonical I/C/P stay with their actual native producers; these journals contain no keys,
 * credentials, certificates or replacement proof. Exclusive forced writes precede effects. */
public class LocalFreshDevicePairingIntents(
    private val targetStorage: LocalSetupIntentStorage,
    private val masterStorage: LocalSetupIntentStorage,
) {
    private val monitor = LocalSetupMonitor()

    public data class TargetIdentity(public val intentId: DomainId, public val scope: BranchScope,
        public val capabilities: Set<String>, public val appId: String, public val deviceName: String)
    public data class TargetRecord(public val identity: TargetIdentity, public val preparationAttempted: Boolean,
        public val originalPreparationId: DomainId?, public val deviceId: DomainId?, public val keyId: DomainId?,
        public val sdkAttempted: Boolean, public val acquisitionId: DomainId?, public val sdkDigest: String?,
        public val responseActionId: DomainId?, public val challengeDigest: String?, public val possessionDigest: String?)
    public data class MasterIdentity(public val context: LocalDeviceAdmissionIntents.Context, public val requestId: DomainId,
        public val acquisitionId: DomainId, public val originalPreparationId: DomainId,
        public val deviceId: DomainId, public val keyId: DomainId, public val sdkDigest: String)
    public data class MasterRecord(public val identity: MasterIdentity, public val preparationAttempted: Boolean,
        public val actionId: DomainId?, public val challengeDigest: String?,
        public val proofAttempted: Boolean, public val possessionDigest: String?,
        public val retirementAttempted: Boolean = false, public val retirementDigest: String? = null)
    public data class ResponseCycle(public val index: Int, public val retiredActionId: DomainId,
        public val retiredChallengeDigest: String, public val retirementDigest: String,
        public val previousResponseId: DomainId, public val allocationResponseId: DomainId?,
        public val responseActionId: DomainId?, public val challengeDigest: String?, public val possessionDigest: String?)

    public fun target(): TargetRecord? = withSetupLock(monitor) {
        val files = files(targetStorage, TARGET_KINDS)
        if (files.isEmpty()) return@withSetupLock null
        require(files.map { it.first }.distinct().size == 1 && files.any { it.second == "intent" })
        val id = files.first().first
        val base = read(targetStorage, id, "intent")
        require(base.size >= 9 && base[1] == "intent" && base[2] == id.value)
        val count = base[8].toInt()
        require(count in 0..64 && base.size == 9 + count && base[8] == count.toString())
        val codes = base.drop(9)
        require(codes == codes.distinct().sorted())
        val identity = TargetIdentity(id, BranchScope(DomainId(base[3]), DomainId(base[4]), DomainId(base[5])),
            codes.toSet(), base[6], base[7])
        validateTarget(identity)
        var preparation: DomainId? = null; var device: DomainId? = null; var key: DomainId? = null
        var acquisition: DomainId? = null; var sdkDigest: String? = null
        var action: DomainId? = null; var challenge: String? = null; var possession: String? = null
        val kinds = files.map { it.second }.toSet()
        for ((_, kind) in files.filter { it.second in BASE_TARGET_KINDS }) {
            val actual = read(targetStorage, id, kind)
            val expected = targetFields(identity, kind)
            require(actual.take(expected.size) == expected)
            val extra = actual.drop(expected.size)
            when (kind) {
                "target" -> { require(extra.size == 3); preparation = DomainId(extra[0]); device = DomainId(extra[1]); key = DomainId(extra[2]) }
                "identity" -> { require(extra.size == 2); acquisition = DomainId(extra[0]); sdkDigest = digest(extra[1]) }
                "respond" -> { require(extra.size == 2); action = DomainId(extra[0]); challenge = digest(extra[1]) }
                "possession" -> { require(extra.size == 1); possession = digest(extra[0]) }
                else -> require(extra.isEmpty())
            }
        }
        require("target" !in kinds || "prepare" in kinds)
        require("sdk" !in kinds || "target" in kinds)
        require("identity" !in kinds || "sdk" in kinds)
        require("respond" !in kinds || "identity" in kinds)
        require("possession" !in kinds || "respond" in kinds)
        require(listOfNotNull(preparation, device, key, acquisition, action).distinct().size ==
            listOfNotNull(preparation, device, key, acquisition, action).size)
        targetStorage.verify()
        val result = TargetRecord(identity, "prepare" in kinds, preparation, device, key, "sdk" in kinds,
            acquisition, sdkDigest, action, challenge, possession)
        readResponseCycles(result, files)
        result
    }

    public fun beginTarget(intentId: DomainId, scope: BranchScope, capabilities: Set<String>, appId: String,
        deviceName: String): TargetRecord = withSetupLock(monitor) {
        val identity = TargetIdentity(intentId, scope, capabilities.toSet(), appId, deviceName)
        validateTarget(identity)
        require(target() == null)
        write(targetStorage, intentId, "intent", targetFields(identity, "intent"), false)
        checkNotNull(target())
    }
    public fun markTargetPreparation(intentId: DomainId): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId); require(!before.preparationAttempted)
        targetWrite(before, "prepare")
    }
    public fun retainTargetReference(intentId: DomainId, original: LocalDevicePairingTargetReference): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId); require(before.preparationAttempted)
        require(listOf(original.originalPreparationId, original.deviceId, original.keyId).distinct().size == 3)
        if (before.originalPreparationId != null) require(before.originalPreparationId == original.originalPreparationId &&
            before.deviceId == original.deviceId && before.keyId == original.keyId)
        targetWrite(before, "target", listOf(original.originalPreparationId.value, original.deviceId.value, original.keyId.value), true)
    }
    public fun markTargetSdkAttempt(intentId: DomainId): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId); require(before.originalPreparationId != null && !before.sdkAttempted)
        targetWrite(before, "sdk")
    }
    public fun retainTargetSdkIdentity(intentId: DomainId, identity: LocalDevicePairingSdkIdentityV2): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId)
        require(before.sdkAttempted && identity.originalPreparationId == before.originalPreparationId &&
            identity.target.admission.deviceId == before.deviceId && identity.target.keyId == before.keyId &&
            identity.target.admission.scope == before.identity.scope &&
            identity.target.admission.capabilities == before.identity.capabilities && identity.appId.value == before.identity.appId)
        targetWrite(before, "identity", listOf(identity.originalAcquisitionId.value, identity.descriptorDigest.value), true)
    }
    public fun markTargetResponse(intentId: DomainId, challenge: LocalDevicePairingChallenge): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId); val sdk = checkNotNull(challenge.requested.sdkIdentity)
        require(before.sdkDigest != null && before.responseActionId == null && sdk.descriptorDigest.value == before.sdkDigest &&
            sdk.originalAcquisitionId == before.acquisitionId && sdk.originalPreparationId == before.originalPreparationId &&
            sdk.target.admission.scope == before.identity.scope && sdk.target.admission.capabilities == before.identity.capabilities &&
            sdk.target.admission.deviceId == before.deviceId && sdk.target.keyId == before.keyId)
        targetWrite(before, "respond", listOf(challenge.originalActionId.value, challenge.challengeDigest.value))
    }
    public fun retainTargetPossession(intentId: DomainId, possession: LocalDevicePairingPossession): TargetRecord = withSetupLock(monitor) {
        val before = originalTarget(intentId)
        require(possession.originalActionId == before.responseActionId && possession.challengeDigest.value == before.challengeDigest &&
            possession.deviceId == before.deviceId && possession.keyId == before.keyId && possession.sdkIdentityDigest?.value == before.sdkDigest)
        val bytes = possession.copyWireBytes()
        try { targetWrite(before, "possession", listOf(sha256(bytes).value), true) } finally { bytes.fill(0) }
    }

    public fun masters(context: LocalDeviceAdmissionIntents.Context): List<MasterRecord> = withSetupLock(monitor) {
        val files = files(masterStorage, MASTER_KINDS)
        val records = files.groupBy { it.first }.map { (id, entries) ->
            require(entries.any { it.second == "intent" })
            val base = read(masterStorage, id, "intent")
            require(base.size == 13 && base[1] == "intent" && base[7] == id.value)
            val actualContext = LocalDeviceAdmissionIntents.Context(DomainId(base[2]),
                BranchScope(DomainId(base[3]), DomainId(base[4]), DomainId(base[5])), DomainId(base[6]))
            require(actualContext == context)
            val identity = MasterIdentity(actualContext, id, DomainId(base[8]), DomainId(base[9]),
                DomainId(base[10]), DomainId(base[11]), digest(base[12]))
            validateMaster(identity)
            var action: DomainId? = null; var challenge: String? = null; var possession: String? = null
            var retirement: String? = null
            val kinds = entries.map { it.second }.toSet()
            for ((_, kind) in entries) {
                val actual = read(masterStorage, id, kind); val expected = masterFields(identity, kind)
                require(actual.take(expected.size) == expected)
                val extra = actual.drop(expected.size)
                when (kind) {
                    "action" -> { require(extra.size == 2); action = DomainId(extra[0]); challenge = digest(extra[1]) }
                    "prove" -> { require(extra.size == 1); possession = digest(extra[0]) }
                    "retired" -> { require(extra.size == 1); retirement = digest(extra[0]) }
                    else -> require(extra.isEmpty())
                }
            }
            require("action" !in kinds || "prepare" in kinds)
            require("prove" !in kinds || "action" in kinds)
            require("retire" !in kinds || "action" in kinds)
            require("retired" !in kinds || "retire" in kinds)
            val ids = listOfNotNull(id, identity.acquisitionId, identity.originalPreparationId, identity.deviceId, identity.keyId, action)
            require(ids.distinct().size == ids.size)
            MasterRecord(identity, "prepare" in kinds, action, challenge, "prove" in kinds, possession, "retire" in kinds, retirement)
        }.sortedBy { it.identity.requestId.value }
        require(records.size <= MAXIMUM_MASTER_RECORDS && records.count { it.actionId == null } <= 1)
        if (files.isNotEmpty()) masterStorage.verify()
        records
    }
    public fun master(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId): MasterRecord =
        masters(context).single { it.identity.requestId == requestId }
    public fun beginMaster(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId,
        identity: LocalDevicePairingSdkIdentityV2): MasterRecord = withSetupLock(monitor) {
        val records = masters(context)
        require(records.size < MAXIMUM_MASTER_RECORDS && records.none { it.identity.requestId == requestId || it.actionId == null } &&
            identity.target.admission.scope == context.scope && identity.target.admission.deviceId != context.cohostDeviceId)
        val original = MasterIdentity(context, requestId, identity.originalAcquisitionId, identity.originalPreparationId,
            identity.target.admission.deviceId, identity.target.keyId, identity.descriptorDigest.value)
        validateMaster(original)
        write(masterStorage, requestId, "intent", masterFields(original, "intent"), false)
        master(context, requestId)
    }
    public fun markMasterPreparation(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId): MasterRecord = withSetupLock(monitor) {
        val before = master(context, requestId); require(!before.preparationAttempted)
        masterWrite(before, "prepare")
    }
    public fun retainMasterAction(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId,
        action: LocalDevicePairingAction): MasterRecord = withSetupLock(monitor) {
        val before = master(context, requestId); val sdk = checkNotNull(action.requested.sdkIdentity)
        require(before.preparationAttempted && action.requested.request.requestId == requestId &&
            action.requested.request.scope == context.scope && action.challenge.cohostDeviceId == context.cohostDeviceId &&
            action.challenge.reviewContext.coordinates.installationId == context.installationId &&
            sdk.descriptorDigest.value == before.identity.sdkDigest && sdk.originalAcquisitionId == before.identity.acquisitionId &&
            sdk.originalPreparationId == before.identity.originalPreparationId && sdk.target.admission.deviceId == before.identity.deviceId &&
            sdk.target.keyId == before.identity.keyId)
        masterWrite(before, "action", listOf(action.originalActionId.value, action.challenge.challengeDigest.value), true)
    }
    public fun markMasterProof(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId,
        possession: LocalDevicePairingPossession): MasterRecord = withSetupLock(monitor) {
        val before = master(context, requestId)
        require(!before.proofAttempted && possession.originalActionId == before.actionId &&
            possession.challengeDigest.value == before.challengeDigest && possession.deviceId == before.identity.deviceId &&
            possession.keyId == before.identity.keyId && possession.sdkIdentityDigest?.value == before.identity.sdkDigest)
        val bytes = possession.copyWireBytes()
        try { masterWrite(before, "prove", listOf(sha256(bytes).value)) } finally { bytes.fill(0) }
    }

    public fun markMasterRetirement(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId): MasterRecord = withSetupLock(monitor) {
        val before = master(context, requestId); require(before.actionId != null && !before.retirementAttempted)
        masterWrite(before, "retire")
    }
    public fun retainMasterRetirement(context: LocalDeviceAdmissionIntents.Context, requestId: DomainId,
        retirement: LocalDevicePairingUnsignedRetirement): MasterRecord = withSetupLock(monitor) {
        val before = master(context, requestId)
        require(before.retirementAttempted && retirement.originalActionId == before.actionId &&
            retirement.originalChallenge.challengeDigest.value == before.challengeDigest)
        masterWrite(before, "retired", listOf(retirement.dispositionDigest.value), true)
    }

    /** Additive response-cycle records preserve the original V1 intent and every prior attempt. */
    public fun responseCycles(intentId: DomainId): List<ResponseCycle> = withSetupLock(monitor) {
        readResponseCycles(originalTarget(intentId), files(targetStorage, TARGET_KINDS))
    }
    public fun markTargetContinuation(intentId: DomainId, retirement: LocalDevicePairingUnsignedRetirement,
        previousResponseId: DomainId): ResponseCycle = withSetupLock(monitor) {
        val base = originalTarget(intentId); val cycles = responseCycles(intentId)
        require(base.sdkDigest != null && cycles.size < LocalDevicePairingProfileV1.MAXIMUM_ACTIONS - 1)
        val prior = cycles.lastOrNull(); require(prior == null || prior.allocationResponseId != null)
        val challenge = retirement.originalChallenge; val sdk = checkNotNull(challenge.requested.sdkIdentity)
        require(sdk.originalPreparationId == base.originalPreparationId && sdk.originalAcquisitionId == base.acquisitionId &&
            sdk.descriptorDigest.value == base.sdkDigest && sdk.target.admission.deviceId == base.deviceId &&
            sdk.target.keyId == base.keyId && sdk.target.admission.scope == base.identity.scope &&
            sdk.target.admission.capabilities == base.identity.capabilities)
        require(challenge.reviewContext.previousUnsignedRetirementDigest?.value == prior?.retirementDigest)
        val oldAction = prior?.responseActionId ?: if (prior == null) base.responseActionId else null
        val oldChallenge = prior?.challengeDigest ?: if (prior == null) base.challengeDigest else null
        oldAction?.let { require(it == retirement.originalActionId && oldChallenge == challenge.challengeDigest.value) }
        if (oldAction != null) require((prior?.possessionDigest ?: if (prior == null) base.possessionDigest else null) != null)
        prior?.allocationResponseId?.let { require(it == previousResponseId) }
        require(previousResponseId !in listOfNotNull(base.originalPreparationId, base.deviceId, base.keyId, base.acquisitionId,
            retirement.originalActionId))
        val index = cycles.size + 1
        write(targetStorage, intentId, "continue$index", targetFields(base.identity, "continue$index") + listOf(
            retirement.originalActionId.value, challenge.challengeDigest.value, retirement.dispositionDigest.value,
            previousResponseId.value), false)
        responseCycles(intentId).last()
    }
    public fun retainTargetContinuation(intentId: DomainId, retirement: LocalDevicePairingUnsignedRetirement,
        original: LocalDevicePairingTargetReference): ResponseCycle = withSetupLock(monitor) {
        val base = originalTarget(intentId); val cycle = responseCycles(intentId).last()
        require(cycle.retirementDigest == retirement.dispositionDigest.value && cycle.retiredActionId == retirement.originalActionId &&
            original.originalPreparationId == base.originalPreparationId && original.deviceId == base.deviceId && original.keyId == base.keyId &&
            original.originalSdkAcquisitionId == base.acquisitionId && original.sdkIdentityDigest?.value == base.sdkDigest)
        require(original.phase in setOf(LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY, LocalDevicePairingTargetPhase.RESPONSE_COMPLETED))
        val response = checkNotNull(original.originalResponseId)
        require(response != cycle.previousResponseId)
        write(targetStorage, intentId, "allocation${cycle.index}", targetFields(base.identity, "allocation${cycle.index}") +
            listOf(response.value, cycle.retirementDigest), true)
        responseCycles(intentId).last()
    }
    public fun markSuccessorTargetResponse(intentId: DomainId, challenge: LocalDevicePairingChallenge): ResponseCycle = withSetupLock(monitor) {
        val base = originalTarget(intentId); val cycle = responseCycles(intentId).last(); val sdk = checkNotNull(challenge.requested.sdkIdentity)
        require(cycle.allocationResponseId != null && cycle.responseActionId == null &&
            challenge.reviewContext.previousUnsignedRetirementDigest?.value == cycle.retirementDigest &&
            sdk.descriptorDigest.value == base.sdkDigest && sdk.originalPreparationId == base.originalPreparationId &&
            sdk.originalAcquisitionId == base.acquisitionId && sdk.target.admission.deviceId == base.deviceId && sdk.target.keyId == base.keyId)
        write(targetStorage, intentId, "respond${cycle.index}", targetFields(base.identity, "respond${cycle.index}") +
            listOf(challenge.originalActionId.value, challenge.challengeDigest.value), false)
        responseCycles(intentId).last()
    }
    public fun retainSuccessorTargetPossession(intentId: DomainId, possession: LocalDevicePairingPossession): ResponseCycle = withSetupLock(monitor) {
        val base = originalTarget(intentId); val cycle = responseCycles(intentId).last()
        require(possession.originalActionId == cycle.responseActionId && possession.challengeDigest.value == cycle.challengeDigest &&
            possession.deviceId == base.deviceId && possession.keyId == base.keyId && possession.sdkIdentityDigest?.value == base.sdkDigest)
        val bytes = possession.copyWireBytes()
        try { write(targetStorage, intentId, "possession${cycle.index}", targetFields(base.identity, "possession${cycle.index}") +
            listOf(sha256(bytes).value), true) } finally { bytes.fill(0) }
        responseCycles(intentId).last()
    }
    private fun readResponseCycles(base: TargetRecord, files: List<Pair<DomainId, String>>): List<ResponseCycle> {
        val entries = files.filter { it.second !in BASE_TARGET_KINDS }.groupBy { checkNotNull(CYCLE_KIND.matchEntire(it.second)).groupValues[2].toInt() }
        require(entries.keys.sorted() == (1..entries.size).toList())
        val result = mutableListOf<ResponseCycle>()
        entries.entries.sortedBy { it.key }.forEach { (index, kinds) ->
            require(base.sdkDigest != null && index < LocalDevicePairingProfileV1.MAXIMUM_ACTIONS && kinds.any { it.second == "continue$index" })
            fun extra(kind: String): List<String>? {
                if (kinds.none { it.second == "$kind$index" }) return null
                val actualKind = "$kind$index"; val actual = read(targetStorage, base.identity.intentId, actualKind)
                val prefix = targetFields(base.identity, actualKind); require(actual.take(prefix.size) == prefix)
                return actual.drop(prefix.size)
            }
            val intent = checkNotNull(extra("continue")); require(intent.size == 4)
            val oldAction = DomainId(intent[0]); val oldChallenge = digest(intent[1]); val retirement = digest(intent[2]); val previousResponse = DomainId(intent[3])
            val allocation = extra("allocation")?.also { require(it.size == 2 && it[1] == retirement) }?.let { DomainId(it[0]) }
            val response = extra("respond")?.also { require(it.size == 2 && allocation != null) }
            val possession = extra("possession")?.also { require(it.size == 1 && response != null) }?.let { digest(it[0]) }
            val prior = result.lastOrNull()
            if (prior != null) require(prior.allocationResponseId != null && prior.allocationResponseId == previousResponse)
            val priorAction = prior?.responseActionId ?: if (prior == null) base.responseActionId else null
            if (priorAction != null) require(priorAction == oldAction && (prior?.challengeDigest ?: base.challengeDigest) == oldChallenge &&
                (prior?.possessionDigest ?: if (prior == null) base.possessionDigest else null) != null)
            allocation?.let { require(it != previousResponse && it !in listOfNotNull(base.originalPreparationId, base.deviceId, base.keyId, base.acquisitionId, oldAction) &&
                result.none { c -> c.allocationResponseId == it || c.previousResponseId == it }) }
            val newAction = response?.let { DomainId(it[0]) }; val challenge = response?.let { digest(it[1]) }
            newAction?.let { require(it != oldAction && result.none { c -> c.responseActionId == it || c.retiredActionId == it }) }
            require(result.none { it.retirementDigest == retirement || it.retiredActionId == oldAction })
            result.add(ResponseCycle(index, oldAction, oldChallenge, retirement, previousResponse, allocation, newAction, challenge, possession))
        }
        return result
    }

    private fun originalTarget(id: DomainId): TargetRecord = checkNotNull(target()).also { require(it.identity.intentId == id) }
    private fun targetWrite(before: TargetRecord, kind: String, extra: List<String> = emptyList(), exact: Boolean = false): TargetRecord {
        write(targetStorage, before.identity.intentId, kind, targetFields(before.identity, kind) + extra, exact)
        return originalTarget(before.identity.intentId)
    }
    private fun masterWrite(before: MasterRecord, kind: String, extra: List<String> = emptyList(), exact: Boolean = false): MasterRecord {
        write(masterStorage, before.identity.requestId, kind, masterFields(before.identity, kind) + extra, exact)
        return master(before.identity.context, before.identity.requestId)
    }
    private fun targetFields(identity: TargetIdentity, kind: String): List<String> = listOf(FORMAT, kind,
        identity.intentId.value, identity.scope.organizationId.value, identity.scope.companyId.value, identity.scope.branchId.value,
        identity.appId, identity.deviceName, identity.capabilities.size.toString()) + identity.capabilities.sorted()
    private fun masterFields(identity: MasterIdentity, kind: String): List<String> = listOf(FORMAT, kind,
        identity.context.installationId.value, identity.context.scope.organizationId.value, identity.context.scope.companyId.value,
        identity.context.scope.branchId.value, identity.context.cohostDeviceId.value, identity.requestId.value,
        identity.acquisitionId.value, identity.originalPreparationId.value, identity.deviceId.value, identity.keyId.value, identity.sdkDigest)
    private fun validateTarget(identity: TargetIdentity) {
        LocalDevicePairingProfileV2.validateAcquisitionText(identity.appId)
        LocalDevicePairingProfileV2.validateAcquisitionText(identity.deviceName)
        require(listOf(identity.scope.organizationId, identity.scope.companyId, identity.scope.branchId).distinct().size == 3)
        require(identity.capabilities.size <= 64 && identity.capabilities.all { it.length in 1..64 && CAPABILITY.matches(it) })
    }
    private fun validateMaster(identity: MasterIdentity) {
        val context = identity.context
        val contextIds = listOf(context.installationId, context.scope.organizationId, context.scope.companyId,
            context.scope.branchId, context.cohostDeviceId)
        val targetIds = listOf(identity.requestId, identity.acquisitionId, identity.originalPreparationId, identity.deviceId, identity.keyId)
        require(contextIds.distinct().size == contextIds.size && targetIds.distinct().size == targetIds.size &&
            targetIds.none { it in contextIds })
        digest(identity.sdkDigest)
    }
    private fun files(storage: LocalSetupIntentStorage, allowed: Set<String>): List<Pair<DomainId, String>> {
        val names = storage.names() ?: return emptyList()
        require(names.size <= MAXIMUM_FILES)
        val files = names.map { name ->
            val match = checkNotNull(NAME.matchEntire(name)); val kind = match.groupValues[2]
            require(kind in allowed); DomainId(match.groupValues[1]) to kind
        }
        require(files.distinct().size == files.size)
        return files
    }
    private fun read(storage: LocalSetupIntentStorage, id: DomainId, kind: String): List<String> {
        val bytes = storage.read(filename(id, kind))
        try {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            require(lines.size >= 4 && lines.first() == FORMAT && lines.last().isEmpty())
            val fields = lines.dropLast(2); val encoded = encode(fields)
            try { require(encoded.contentEquals(bytes)) } finally { encoded.fill(0) }
            return fields
        } finally { bytes.fill(0) }
    }
    private fun write(storage: LocalSetupIntentStorage, id: DomainId, kind: String, fields: List<String>, exact: Boolean) {
        val bytes = encode(fields)
        try {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val name = filename(id, kind)
            // The adapter receives a disposable buffer, not the expected acknowledgement.
            // Even a valid rewritten input must not change the reviewed original binding.
            val supplied = bytes.copyOf()
            try { storage.writeExclusive(name, supplied, exact) } finally { supplied.fill(0) }
            // An acknowledged different/missing binding cannot enable the next native call.
            val retained = storage.read(name)
            try { require(retained.contentEquals(bytes)) } finally { retained.fill(0) }
            storage.verify()
        }
        finally { bytes.fill(0) }
    }
    private fun encode(fields: List<String>): ByteArray {
        require(fields.all { it.isNotEmpty() && '\n' !in it && '\r' !in it })
        val body = fields.joinToString("\n", postfix = "\n").encodeToByteArray()
        return try { body + "sha256:${sha256(body).value}\n".encodeToByteArray() } finally { body.fill(0) }
    }
    private fun filename(id: DomainId, kind: String): String = "${id.value}.$kind.v1"
    private fun digest(value: String): String = value.also { require(DIGEST.matches(it)) }
    override fun toString(): String = "LocalFreshDevicePairingIntents([REDACTED])"
    public companion object {
        public const val MAXIMUM_BYTES: Int = 8192
        public const val MAXIMUM_MASTER_RECORDS: Int = 256
        public const val MAXIMUM_FILES: Int = MAXIMUM_MASTER_RECORDS * 6
        private const val FORMAT = "ukkera.fresh-device-pairing-ui-intent/v1"
        private val BASE_TARGET_KINDS = setOf("intent", "prepare", "target", "sdk", "identity", "respond", "possession")
        private val TARGET_KINDS = BASE_TARGET_KINDS + (1 until LocalDevicePairingProfileV1.MAXIMUM_ACTIONS).flatMap { i ->
            listOf("continue$i", "allocation$i", "respond$i", "possession$i") }
        private val MASTER_KINDS = setOf("intent", "prepare", "action", "prove", "retire", "retired")
        private val CYCLE_KIND = Regex("(continue|allocation|respond|possession)([1-9][0-9]*)")
        private val NAME = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([a-z]+[0-9]*)\\.v1")
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val CAPABILITY = Regex("[A-Za-z0-9._-]+")
    }
}
