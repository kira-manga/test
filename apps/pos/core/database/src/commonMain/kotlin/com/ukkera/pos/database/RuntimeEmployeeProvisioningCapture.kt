package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Closed memory-only producer attached before the D+5 protected INSERTs. The actual native
 * binding is already owned; unchanged database codecs qualify its complete requested data.
 * Only hashes survive the constructor; native-trigger rows independently join key/order/fate. */
internal class RuntimeEmployeeProvisioningCapture(
    val native: NativeEmployeeProvisioningFrame,
    definition: LocalAccountProvisioning,
    account: ProtectedLocalAccount,
    configuration: LocalAuthConfiguration,
    retainedAdmissions: List<LocalDeviceAdmission>,
    private val event: LocalAuthEvent,
) {
    val request: LocalProvisioningRequest = native.requested.request
    val scope: BranchScope get() = request.scope
    private val expectedImages: List<LocalEmployeeEnrollmentProtectedImage>
    private val admissionDigests: List<Pair<DomainId, PayloadDigest>>
    val images = mutableListOf<LocalEmployeeEnrollmentProtectedImage>()
    private var absenceDigest: PayloadDigest? = null
    private var preflight: LocalAuthenticationObservation? = null

    init {
        val requested = native.requested
        val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
        val config = LocalAuthenticationCodec.encodeConfiguration(configuration)
        val retainedPolicy = requested.copyCanonicalPolicy(); val retainedConfig = requested.copyCanonicalConfiguration()
        try {
            check(policy.contentEquals(retainedPolicy) && config.contentEquals(retainedConfig) &&
                definition.actorId == request.targetId && definition.login == requested.definition.login &&
                definition.tokenPolicy == requested.definition.tokenPolicy &&
                definition.admittedDeviceIds == requested.definition.admittedDeviceIds &&
                requested.credentialWasProvided == (account.credential != null) &&
                localAccountProvisioningRequestV1(scope, request.requestId, definition.actorId, account.credential != null,
                    policy.copyOf(), config.copyOf(), definition.admittedDeviceIds) == request)
            val state = account.state
            check(state.scope == scope && state.actorId == request.targetId && state.enabled && state.revision == Revision(0) &&
                state.generation == SessionGeneration(0) && state.sessions.isEmpty() && account.credentialRevision == Revision(0) &&
                event.scope == scope && event.eventId == request.requestId && event.kind == LocalAuthEventKind.ACCOUNT_PROVISIONED &&
                event.targetId == request.targetId && event.operatorId == null && event.sessionId == null &&
                event.accountRevision == Revision(0) && event.contentDigest == request.contentDigest)
            admissionDigests = retainedAdmissions.sortedBy { it.deviceId.value }.map {
                it.deviceId to digest(ProtectedLocalSecurityCodec.encodeAdmission(it))
            }
            val retained = requested.admissions.map { it.deviceId to digest(ProtectedLocalSecurityCodec.encodeAdmission(it)) }
            check(admissionDigests == retained && admissionDigests.size in 1..256 &&
                retainedAdmissions.filter { it.admitted }.map { it.deviceId }.toSet() == definition.admittedDeviceIds)
            val actor = request.targetId
            expectedImages = listOf(
                image(ProtectedLocalTable.ACCOUNT, actor, "", ProtectedLocalSecurityCodec.encodeAccount(account)),
                LocalEmployeeEnrollmentProtectedImage(ProtectedLocalTable.EFFECTIVE_POLICY, actor, "", sha256(policy)),
                LocalEmployeeEnrollmentProtectedImage(ProtectedLocalTable.AUTH_CONFIGURATION, actor, "", sha256(config)),
                throttleImage(actor, ThrottleDimension.Account),
            ) + retainedAdmissions.sortedBy { it.deviceId.value }.map { throttleImage(actor, ThrottleDimension.Device(it.deviceId)) } +
                image(ProtectedLocalTable.AUTH_EVENT, request.requestId, "", LocalAuthenticationCodec.encodeEvent(event))
        } finally { policy.fill(0); config.fill(0); retainedPolicy.fill(0); retainedConfig.fill(0) }
    }

    /** Actual full absence/account census and actual protected time, on the same writer before effects. */
    fun bindPreflight(accountIds: Set<DomainId>, observed: RoomLocalAuthorizationObservation) {
        check(absenceDigest == null && preflight == null && images.isEmpty())
        absenceDigest = localEmployeeEnrollmentAbsenceDigestV1(request, native.requested.definition.login,
            accountIds, admissionDigests.map { it.first }.toSet())
        val binding = native.bindingObservation; val current = observed.publicValue()
        check(current.issuerContextId == binding.issuerContextId && current.moment.bootId == binding.moment.bootId &&
            current.moment.elapsedMillis >= event.at.elapsedMillis && event.at.elapsedMillis >= binding.moment.elapsedMillis)
        preflight = current
    }

    /** Only RoomLocalSecurityTransaction.sealOwned may supply these actual plaintext hashes. */
    fun captureImage(binding: ProtectedLocalBinding, commitment: PayloadDigest) {
        check(binding.key.scope == scope && binding.revision == Revision(0) && images.size < expectedImages.size &&
            preflight != null && absenceDigest != null)
        val dimension = (binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: ""
        val image = LocalEmployeeEnrollmentProtectedImage(binding.key.table, binding.key.recordId, dimension, commitment)
        check(image == expectedImages[images.size]); images += image
    }
    fun evidence(): LocalEmployeeEnrollmentEffectEvidence {
        check(images == expectedImages)
        return LocalEmployeeEnrollmentEffectEvidence(request, native.requested.definitionDigest, native.authorityDigest,
            native.nativeBindingDigest, event.at, checkNotNull(preflight), checkNotNull(absenceDigest), admissionDigests, images)
    }
    private fun throttleImage(actor: DomainId, dimension: ThrottleDimension): LocalEmployeeEnrollmentProtectedImage {
        val key = ThrottleKey(scope, actor, dimension)
        val value = PersistentThrottleState(key, Revision(0), 0, event.at, 0, null)
        val dimensionKey = (ProtectedLocalRecordKey.Throttle(key)).dimensionKey
        return image(ProtectedLocalTable.THROTTLE, actor, dimensionKey, ProtectedLocalSecurityCodec.encodeThrottle(value))
    }
    private fun image(table: ProtectedLocalTable, id: DomainId, dimension: String, bytes: ByteArray): LocalEmployeeEnrollmentProtectedImage =
        LocalEmployeeEnrollmentProtectedImage(table, id, dimension, digest(bytes))
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    override fun toString(): String = "RuntimeEmployeeProvisioningCapture([REDACTED])"
}

internal fun LocalEmployeeEnrollmentProtectedImage.runtimeKey(scope: BranchScope): ByteArray {
    val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == this.table }
    return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value) + if (dimension.isEmpty()) emptyList() else listOf(dimension))
}
