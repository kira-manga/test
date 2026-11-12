package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.AndroidEmployeeClientCustody
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.NativeEmployeeJournalAllocation
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest

/** Closed trusted binding of actual final service/native capabilities. This is not a scalar
 * actor/path/key constructor or a supplied liveness callback. Original remains first-actor-only;
 * employee owns only its separate intent namespace and no device credential journal.
 * PairedDevice consumes only real protected additional-device credential custody. Its
 * separate actor-allocation history issues PairedActor only after actual Master Current. */
internal sealed class AndroidClientJournalBinding : ClientJournalBindingFacts {
    abstract override fun isCurrent(): Boolean
    abstract override fun permitsEnrollment(): Boolean
    abstract override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
        keyId: DomainId, protection: SelfOwnedKeyProtectionV1)
    abstract override val rootName: String
    abstract override val keyAliasNamespace: String
    abstract override val headAliasNamespace: String
    abstract override val aeadDomain: String
    abstract override val headDigestDomain: String
    override val fixedKind: ClientJournalKind? get() = null
    override val employeeEnrollmentCommitment: String? get() = null
    override val pairedDeviceEnrollmentCommitment: String? get() = null
    override val pairedActorEnrollmentCommitment: String? get() = null

    class Original(val original: AndroidOriginalLocalClient): AndroidClientJournalBinding() {
        override fun isCurrent(): Boolean = original.isCurrent()
        override fun permitsEnrollment(): Boolean = original.permitsFirstEnrollment()
        override val rootName: String get() = if (original.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "ukkera-local-client-journals-training-v1"
            else "ukkera-local-client-journals-v1"
        override val keyAliasNamespace: String get() = if (original.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "com.ukkera.pos.client-journal.training.v1/"
            else "com.ukkera.pos.client-journal.v1/"
        override val headAliasNamespace: String get() = if (original.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "com.ukkera.pos.client-journal-head.training.v1/"
            else "com.ukkera.pos.client-journal-head.v1/"
        override val aeadDomain: String get() = "ukkera.android.client-journal-aead/v1"
        override val headDigestDomain: String get() = "ukkera.android.client-journal-head/v1"
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
            keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
            val c = original.material.coordinates
            val kind = if (actor == null) ClientJournalKind.CREDENTIALS else ClientJournalKind.INTENTS
            check(owner == LocalClientDeviceIdentity(c.scope, c.deviceId) && (actor == null || actor == c.firstActorId) &&
                journalId == originalClientJournalId(original, kind, "journal") && keyId == originalClientJournalId(original, kind, "key") &&
                protection == original.material.protection)
        }
    }
    class Employee(val custody: AndroidEmployeeClientCustody, val allocation: NativeEmployeeJournalAllocation): AndroidClientJournalBinding() {
        init { check(allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()) }
        override fun isCurrent(): Boolean = custody.isCurrent() && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed()
        override fun permitsEnrollment(): Boolean = allocation.action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE && isCurrent()
        override val rootName: String get() = if (custody.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "ukkera-local-employee-intent-journals-training-v1"
            else "ukkera-local-employee-intent-journals-v1"
        override val keyAliasNamespace: String get() = if (custody.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "com.ukkera.pos.employee-intent-journal.training.v1/"
            else "com.ukkera.pos.employee-intent-journal.v1/"
        override val headAliasNamespace: String get() = if (custody.compositionMode ==
            com.ukkera.pos.security.LocalSetupCompositionMode.TRAINING) "com.ukkera.pos.employee-intent-journal-head.training.v1/"
            else "com.ukkera.pos.employee-intent-journal-head.v1/"
        override val aeadDomain: String get() = "ukkera.android.employee-intent-journal-aead/v1"
        override val headDigestDomain: String get() = "ukkera.android.employee-intent-journal-head/v1"
        override val employeeEnrollmentCommitment: String = custody.native.copyOriginalJournalBinding().useWiped { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).useWiped(::encodeHex)
        }
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
            keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
            val c = custody.material.coordinates
            check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == custody.actorId &&
                actor != c.firstActorId && c.scope == custody.scope && c.deviceId == custody.deviceId &&
                journalId == allocation.journalId && keyId == allocation.keyId && journalId != keyId &&
                protection == custody.material.protection && allocation.belongsTo(custody.native) && allocation.originalOpenClaimed())
        }
    }
    class PairedDevice(val custody: PairedDeviceCredentialJournalCustody): AndroidClientJournalBinding() {
        private val profile = custody.approvedOriginal.masterSdkOriginal.compositionProfile
        init { check(custody.originalOpenClaimed()) }
        // This gate is used ONLY during actual acquisition. Successful journal IO owns its
        // independent file/key/head continuity, not the target's later RPC/source lifetime.
        override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalOpenClaimed()
        override fun permitsEnrollment(): Boolean =
            custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
        override val rootName: String get() = pairedRoot("ukkera-local-paired-device-credential-journals-v1", profile)
        override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-credential-journal.v1/", profile)
        override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-credential-journal-head.v1/", profile)
        override val aeadDomain: String get() = "ukkera.android.paired-device-credential-journal-aead/v1"
        override val headDigestDomain: String get() = "ukkera.android.paired-device-credential-journal-head/v1"
        override val pairedDeviceEnrollmentCommitment: String get() = custody.bindingCommitment
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
            keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
            check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == null &&
                journalId == custody.journalId && keyId == custody.keyId && journalId != keyId &&
                protection == custody.protection && custody.originalOpenClaimed())
        }
    }

    /** Separate protected allocation METADATA, initialized before the actual native source
     * handoff. It cannot produce a credential or financial-intent journal view. */
    class PairedActorAllocations(val custody: PairedDeviceCredentialJournalCustody): AndroidClientJournalBinding() {
        private val profile = custody.approvedOriginal.masterSdkOriginal.compositionProfile
        init { check(custody.originalActorAllocationOpenClaimed()) }
        override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalActorAllocationOpenClaimed()
        override fun permitsEnrollment(): Boolean =
            custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
        override val fixedKind: ClientJournalKind get() = ClientJournalKind.ACTOR_ALLOCATIONS
        override val rootName: String get() = pairedRoot("ukkera-local-paired-device-actor-allocations-v1", profile)
        override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-allocation.v1/", profile)
        override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-allocation-head.v1/", profile)
        override val aeadDomain: String get() = "ukkera.android.paired-device-actor-allocation-aead/v1"
        override val headDigestDomain: String get() = "ukkera.android.paired-device-actor-allocation-head/v1"
        override val pairedDeviceEnrollmentCommitment: String get() = custody.bindingCommitment
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
            keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
            check(owner == LocalClientDeviceIdentity(custody.scope, custody.deviceId) && actor == null &&
                journalId == pairedActorAllocationJournalId(custody, "journal") &&
                keyId == pairedActorAllocationJournalId(custody, "key") && journalId != keyId &&
                protection == custody.protection && custody.originalActorAllocationOpenClaimed())
        }
    }

    /** Genuine exact actor allocation and newly authenticated Current, never another
     * cohost's employee, a supplied actor scalar or the device's credential file. */
    class PairedActor(val custody: AndroidPairedDeviceActorJournalCustody): AndroidClientJournalBinding() {
        private val profile = custody.compositionProfile
        init { check(custody.originalOpenClaimed()) }
        // Acquisition-only. Successful INTENTS IO subsequently owns its actual independent
        // worker/head/files, not the authentication or original target's future currentness.
        override fun isCurrent(): Boolean = custody.isCurrent() && custody.originalOpenClaimed()
        override fun permitsEnrollment(): Boolean =
            custody.action == LocalPairedDeviceCredentialJournalAction.ENROLL && isCurrent()
        override val rootName: String get() = pairedRoot("ukkera-local-paired-device-actor-intent-journals-v1", profile)
        override val keyAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-intent-journal.v1/", profile)
        override val headAliasNamespace: String get() = pairedAlias("com.ukkera.pos.paired-device-actor-intent-journal-head.v1/", profile)
        override val aeadDomain: String get() = "ukkera.android.paired-device-actor-intent-journal-aead/v1"
        override val headDigestDomain: String get() = "ukkera.android.paired-device-actor-intent-journal-head/v1"
        override val pairedDeviceEnrollmentCommitment: String get() = custody.deviceBindingCommitment
        override val pairedActorEnrollmentCommitment: String get() = custody.bindingCommitment
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
            keyId: DomainId, protection: SelfOwnedKeyProtectionV1) {
            val identity = custody.identity
            check(owner == LocalClientDeviceIdentity(identity.scope, identity.deviceId) && actor == identity.actorId &&
                journalId == custody.journalId && keyId == custody.keyId && journalId != keyId &&
                protection == custody.protection && custody.originalOpenClaimed())
        }
    }
}
