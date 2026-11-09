package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision

/** One real full-D7 preparation under the original P2/P1 owners. No public verifier/status
 * dependency exists. The exact issued attempt is retained by native before this object can
 * reach a Room initializer; a failed/expired/unknown attempt is never retried with new IDs. */
internal class RoomSelfOwnedInstallationVerifier private constructor(
    internal val custody: SelfOwnedNativeHistoryCustody,
    internal val templates: LocalInstallationSeedTemplatesV2,
    internal val account: ProtectedLocalAccount,
    internal val configuration: LocalAuthConfiguration,
    internal val attempt: OriginalInstallationAttempt,
) {
    internal val snapshot: LocalBranchInstallationSnapshotV2 get() = templates.snapshot
    private var submitted=false
    internal suspend fun begin(owner: RoomRuntimeHistoryOwner): LocalInstallationCommitFactsV2 {
        check(!submitted && owner.originalRecord.record.definition?.definitionDigest==snapshot.definitionDigest &&
            owner.originalRecord.record.attempt?.facts==attempt.facts && owner.originalRecord.record.parent==null)
        submitted=true
        return custody.observeOriginalInstallationCommit(attempt)
    }
    internal fun clear() { templates.clear() }
    internal companion object {
        suspend fun prepare(custody: SelfOwnedNativeHistoryCustody, definition: LocalBranchInstallationDefinitionV2,
            headLogin: String, headSecret: CharArray, password: CharArray): RoomSelfOwnedInstallationVerifier {
            require(password.size in 4..1024 && headLogin.length in 1..128 && headSecret.size in 4..1024)
            val snapshot=LocalBranchInstallationCodecV2.snapshotDefinition(definition)
            val templates=LocalBranchInstallationCodecV2.preflight(snapshot)
            var delivered=false
            try {
                val original=custody.snapshotOriginal()
                check(original.record.allocation.digest==snapshot.allocationDigest && original.record.parent==null &&
                    original.record.pending==null && original.record.database==null && original.record.attempt==null)
                val c=snapshot.definition.allocation.coordinates
                val security=custody.runtimeSecurity
                check(security.storageKey.keyId==c.storageKeyId && security.pepper.keyId==c.pepperKeyId)
                val binding=CredentialBinding(c.scope,c.firstActorId,Revision(0))
                // No Room database/loan exists here. Credential work is real native PBKDF2/pepper,
                // not a preseeded account, test verifier or a hash inside a SQL transaction.
                val credential=when(val result=security.passwords.create(password,binding,security.pepper)) {
                    is PasswordHashingResult.Created -> result.record
                    is PasswordHashingResult.Unavailable -> error("Original employee credential unavailable: ${result.reason}")
                }
                check(credential.binding==binding && credential.pepperReference==security.pepper)
                val account=ProtectedLocalAccount(LocalAccountSessionState(c.scope,c.firstActorId,true,Revision(0),SessionGeneration(0),emptyList()),
                    Revision(0),credential)
                val accountBytes=ProtectedLocalSecurityCodec.encodeAccount(account)
                try {
                    val protected=ProtectedLocalBinding(ProtectedLocalRecordKey.Account(c.scope,c.firstActorId),Revision(0))
                    protected.requirePlaintextSize(accountBytes.size)
                    val decoded=ProtectedLocalSecurityCodec.decodeAccount(protected,accountBytes)
                    val canonical=ProtectedLocalSecurityCodec.encodeAccount(decoded)
                    try { check(canonical.contentEquals(accountBytes)) } finally { canonical.fill(0) }
                } finally { accountBytes.fill(0) }
                val config=LocalAuthConfiguration(c.scope,c.firstActorId,Revision(0),definition.firstAccount.login,definition.firstAccount.tokenPolicy)
                // Full codec capacity preflight preceded the actual same-work P1 approval.
                val action=custody.prepareOriginalInstallation(snapshot)
                val attempt=custody.approveOriginalInstallation(action,headLogin,headSecret)
                check(attempt.snapshot.definitionDigest==snapshot.definitionDigest)
                custody.armOriginalInstallation(attempt) // actual forced ARMED before any DB create
                return RoomSelfOwnedInstallationVerifier(custody,templates,account,config,attempt).also { delivered=true }
            } finally {
                password.fill('\u0000'); headSecret.fill('\u0000')
                if(!delivered) templates.clear()
            }
        }
    }
}
