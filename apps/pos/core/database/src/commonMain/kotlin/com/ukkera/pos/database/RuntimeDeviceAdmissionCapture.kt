package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Closed same-writer producer for exactly N+2 fresh INSERTs. The actual native frame
 * already exists; unchanged protected codecs recapture the entire original definition.
 * Only actual protected sealing supplies image hashes, not a caller frame/callback. */
internal class RuntimeDeviceAdmissionCapture(
    val native: NativeDeviceAdmissionFrame,
    admission: LocalDeviceAdmission,
    retainedAdmissions: List<LocalDeviceAdmission>,
    private val event: LocalAuthEvent,
) {
    val request: LocalProvisioningRequest = native.requested.request
    val scope: BranchScope get() = request.scope
    private val admissionDigests: List<Pair<DomainId, PayloadDigest>>
    private val admissionImage: LocalDeviceAdmissionProtectedImage
    private val eventImage: LocalDeviceAdmissionProtectedImage
    private var accountDigests: List<Pair<DomainId, PayloadDigest>>? = null
    private var expectedImages: List<LocalDeviceAdmissionProtectedImage>? = null
    private var preflight: LocalAuthenticationObservation? = null
    val images = mutableListOf<LocalDeviceAdmissionProtectedImage>()

    init {
        val requested = native.requested
        val sdk = checkNotNull(requested.sdkIdentity)
        val service = ProtectedLocalSecurityCodec.encodeAdmission(admission)
        val retainedService = requested.copyCanonicalServiceAdmission()
        val retainedTargetService = ProtectedLocalSecurityCodec.encodeAdmission(requested.admission)
        try {
            check(service.contentEquals(retainedService) && service.contentEquals(retainedTargetService) &&
                admission.admitted && admission.revision == Revision(0) &&
                localDevicePairingRequestV1(scope, request.requestId, admission.deviceId, service.copyOf()) == request)
            val captured = requested.masterSdkOriginal?.let { master ->
                LocalDevicePairingDefinition.captureMasterBound(request.requestId, requested.target,
                    service, retainedAdmissions, sdk, master)
            } ?: LocalDevicePairingDefinition.captureSdkBound(request.requestId, requested.target,
                service, retainedAdmissions, sdk)
            val actualDefinition = captured.copyWireBytes(); val retainedDefinition = requested.copyWireBytes()
            try { check(actualDefinition.contentEquals(retainedDefinition)) }
            finally { actualDefinition.fill(0); retainedDefinition.fill(0) }
            admissionDigests = retainedAdmissions.sortedBy { it.deviceId.value }.map {
                it.deviceId to digest(ProtectedLocalSecurityCodec.encodeAdmission(it))
            }
            check(admissionDigests.size in 1..255 && admissionDigests.map { it.first }.distinct().size == admissionDigests.size &&
                admission.deviceId !in admissionDigests.map { it.first } &&
                event.scope == scope && event.eventId == request.requestId && event.kind == LocalAuthEventKind.DEVICE_ADMITTED &&
                event.targetId == request.targetId && event.operatorId == null && event.sessionId == null &&
                event.accountRevision == null && event.contentDigest == request.contentDigest)
            admissionImage = LocalDeviceAdmissionProtectedImage(ProtectedLocalTable.ADMISSION, request.targetId, "", sha256(service))
            eventImage = image(ProtectedLocalTable.AUTH_EVENT, request.requestId, "", LocalAuthenticationCodec.encodeEvent(event))
        } finally { service.fill(0); retainedService.fill(0); retainedTargetService.fill(0) }
    }

    /** Complete actual account census/absence and protected observation on this writer. */
    fun bindPreflight(accounts: List<Pair<DomainId, PayloadDigest>>, observed: RoomLocalAuthorizationObservation) {
        check(preflight == null && accountDigests == null && expectedImages == null && images.isEmpty() &&
            accounts.size in 1..4096 && accounts == accounts.sortedBy { it.first.value } &&
            accounts.map { it.first }.distinct().size == accounts.size && request.targetId !in accounts.map { it.first })
        val binding = native.bindingObservation; val current = observed.publicValue()
        check(current.issuerContextId == binding.issuerContextId && current.moment.bootId == binding.moment.bootId &&
            event.at.bootId == binding.moment.bootId && event.at.elapsedMillis >= binding.moment.elapsedMillis &&
            current.moment.elapsedMillis >= event.at.elapsedMillis)
        accountDigests = accounts.toList()
        expectedImages = listOf(admissionImage) + accounts.map { (actor, _) ->
            val key = ThrottleKey(scope, actor, ThrottleDimension.Device(request.targetId))
            val throttle = PersistentThrottleState(key, Revision(0), 0, event.at, 0, null)
            image(ProtectedLocalTable.THROTTLE, actor, ProtectedLocalRecordKey.Throttle(key).dimensionKey,
                ProtectedLocalSecurityCodec.encodeThrottle(throttle))
        } + eventImage
        preflight = current
    }

    fun captureImage(binding: ProtectedLocalBinding, commitment: PayloadDigest) {
        val expected = checkNotNull(expectedImages)
        check(preflight != null && binding.key.scope == scope && binding.revision == Revision(0) && images.size < expected.size)
        val image = LocalDeviceAdmissionProtectedImage(binding.key.table, binding.key.recordId,
            (binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey ?: "", commitment)
        check(image == expected[images.size]); images += image
    }

    fun evidence(): LocalDeviceAdmissionEffectEvidence {
        check(images == checkNotNull(expectedImages))
        return LocalDeviceAdmissionEffectEvidence(request, native.requested.definitionDigest, native.authorityDigest,
            native.nativeBindingDigest, event.at, checkNotNull(preflight), admissionDigests, checkNotNull(accountDigests), images)
    }
    private fun image(table: ProtectedLocalTable, id: DomainId, dimension: String, bytes: ByteArray): LocalDeviceAdmissionProtectedImage =
        LocalDeviceAdmissionProtectedImage(table, id, dimension, digest(bytes))
    private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    override fun toString(): String = "RuntimeDeviceAdmissionCapture([REDACTED])"
}

internal fun LocalDeviceAdmissionProtectedImage.runtimeDeviceAdmissionKey(scope: BranchScope): ByteArray {
    val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == this.table }
    return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value) + if (dimension.isEmpty()) emptyList() else listOf(dimension))
}
