package com.ukkera.pos.security.desktoppublicationsv2

import com.ukkera.pos.security.*
import com.ukkera.pos.security.desktopseamv2.DesktopSeamV2Double
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Self-authored, NOT independent. #87 supporting checks over actual V2 custody and crypto;
 * in-memory OS/key doubles do not qualify Windows/macOS native custody or owner acceptance. */
class DesktopBusinessPublicationCustodyTest {
    private val secret = "synthetic-head-pass-2026"
    private var sequence = 50_000
    private fun id() = DomainId("00000000-0000-4000-8000-%012x".format(sequence++))
    private fun founded(): DesktopSeamV2Double = DesktopSeamV2Double().also { seam ->
        val work = seam.openWork()
        try {
            val core = SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)
            val facts = LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1("Synthetic Publications", "Synthetic Person",
                "installer.one", SelfOwnedKeyProtectionV1.OS_ISOLATED), "Synthetic Head", "head.one",
                LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
            core.completeNewCompanyAndHead(core.prepareNewCompanyAndHead(facts), "synthetic-installer-pass".toCharArray(), secret.toCharArray())
        } finally { seam.closeWork(work) }
    }
    private class Session(val seam: DesktopSeamV2Double, failEndorsement: Boolean = false, failCompanionWrite: Boolean = false) {
        val work = seam.openWork()
        val gate = AndroidLocalCompanyIssuanceGate()
        private val store = if (!failCompanionWrite) seam.store else object : SelfOwnedProvisionerRegistryStoreV2 by seam.store {
            override fun replaceBusinessPublicationEnvelope(bytes: ByteArray) {
                seam.store.replaceBusinessPublicationEnvelope(bytes)
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            }
        }
        val custody = SelfOwnedDesktopCompanyCustodyV2(seam.environment, store, seam.keys, work, gate)
        private val native = JcaBusinessPublicationCustody(seam.environment, work, gate, custody)
        private val port = if (!failEndorsement) native else object : NativeBusinessPublicationCustody by native {
            override fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
                approval: NativeBusinessPublicationApproval): ByteArray = selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }
        val core = LocalCompanyBusinessPublicationCore(port) { error("Nonserial checks must not fabricate native history") }
        fun <T> stage(block: (LocalCompanyBusinessPublicationCore) -> T): AndroidOwnedResult<T> = runBlocking {
            work.run { object : AndroidOwnedTask<T> {
                override fun execute() = block(core)
                override fun discard(value: T) = Unit
                override fun clearInputs() = Unit
            } }
        }
        fun close() = seam.closeWork(work)
    }
    private fun <T> session(seam: DesktopSeamV2Double, failEndorsement: Boolean = false, failCompanionWrite: Boolean = false,
        block: (Session) -> T): T {
        val s = Session(seam, failEndorsement, failCompanionWrite)
        return try { block(s) } finally { s.close() }
    }
    private fun <T> value(result: AndroidOwnedResult<T>) = assertIs<AndroidOwnedResult.Value<T>>(result).value
    private fun unavailable(result: AndroidOwnedResult<*>) = assertIs<AndroidOwnedResult.Unavailable>(result).reason
    private fun proposal(s: Session, kind: CompanyBusinessPublicationKind): CompanyBusinessPublicationProposal {
        val original = checkNotNull(s.custody.companyOriginal())
        return CompanyBusinessPublicationProposal(CompanyScope(original.original.organizationId, original.original.companyId),
            kind, id(), id(), (kind.domain + "synthetic-publication-content").encodeToByteArray())
    }

    @Test fun everyNonserialKindPublishesAndRecoversExactBytesAcrossNativeLifetimes() {
        val seam = founded()
        val outputs = session(seam) { s ->
            CompanyBusinessPublicationKind.entries.filterNot { it.isOriginalSerialKindV1() }.map { kind ->
                val proposal = proposal(s, kind)
                val action = value(s.stage { it.prepare(proposal) })
                assertEquals(CompanyBusinessPublicationPhase.PREPARED, value(s.stage { it.retained() }).last().phase)
                val output = value(s.stage { it.approve(action, "head.one", secret.toCharArray()) })
                val trust = value(s.stage { it.readTrust() })
                val bytes = proposal.copyCanonicalContent()
                assertContentEquals(bytes, output.proposal.copyCanonicalContent())
                assertTrue(trust.verify(proposal.company, output.signingKeyId, bytes, output.signatureHex))
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                assertFalse(trust.verify(proposal.company, output.signingKeyId, bytes, output.signatureHex))
                output
            }
        }
        session(seam) { s ->
            assertEquals(6, value(s.stage { it.retained() }).size)
            for (output in outputs) {
                val recovered = value(s.stage { it.recover(output.originalActionId) })
                assertEquals(output.originalActionId, recovered.originalActionId)
                assertEquals(output.signingKeyId, recovered.signingKeyId)
                assertEquals(output.signatureHex, recovered.signatureHex)
                assertContentEquals(output.proposal.copyCanonicalContent(), recovered.proposal.copyCanonicalContent())
            }
        }
    }

    @Test fun wrongHeadAndAnActionFromAnotherLifetimeCannotApprove() {
        val seam = founded()
        val action = session(seam) { s ->
            val action = value(s.stage { it.prepare(proposal(s, CompanyBusinessPublicationKind.CATALOG_SKU_V1)) })
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED,
                unavailable(s.stage { it.approve(action, "head.one", "wrong-synthetic-pass".toCharArray()) }))
            assertEquals(CompanyBusinessPublicationPhase.PREPARED, value(s.stage { it.retained() }).single().phase)
            action
        }
        seam.elapsed.addAndGet(120_000L)
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED,
                unavailable(s.stage { it.approve(action, "head.one", secret.toCharArray()) }))
            val rejoined = value(s.stage { it.review(action.originalActionId) })
            val output = value(s.stage { it.approve(rejoined, "head.one", secret.toCharArray()) })
            assertEquals(action.originalActionId, output.originalActionId)
        }
    }

    @Test fun tamperedWrongDomainAndForeignCompanyCustodyAreRefused() {
        val seam = founded()
        session(seam) { s -> value(s.stage { it.prepare(proposal(s, CompanyBusinessPublicationKind.CATALOG_SKU_V1)) }) }
        val genuine = checkNotNull(seam.businessPublicationBytes).copyOf()
        val foreign = founded()
        foreign.businessPublicationBytes = genuine.copyOf()
        session(foreign) { s -> assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { it.retained() })) }
        seam.businessPublicationBytes = genuine.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        session(seam) { s -> assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { it.retained() })) }
        val envelope = SelfOwnedNativeCompanyLedgerWireV2.encodeLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS,
            "synthetic-wrong-domain".encodeToByteArray())
        val original = checkNotNull(seam.store.companyBootstrapRecordV2())
        val aad = SelfOwnedNativeCompanyLedgerWireV2.aad(SelfOwnedBusinessPublicationLedgerV2, original)
        val wrong = seam.keys.sealRegistryV2(aad, envelope)
        seam.businessPublicationBytes = SelfOwnedNativeCompanyLedgerWireV2.encodeEnvelope(wrong)
        session(seam) { s -> assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { it.retained() })) }
        seam.businessPublicationBytes = genuine
        session(seam) { s -> assertEquals(1, value(s.stage { it.retained() }).size) }
    }

    @Test fun lostSigningIntentNeverRegeneratesAnOutputOnReopen() {
        val seam = founded()
        val actionId = session(seam, failEndorsement = true) { s ->
            val action = value(s.stage { it.prepare(proposal(s, CompanyBusinessPublicationKind.PRICE_POLICY_V1)) })
            assertEquals(SelfOwnedInstallationFailureV1.UNAVAILABLE,
                unavailable(s.stage { it.approve(action, "head.one", secret.toCharArray()) }))
            action.originalActionId
        }
        session(seam) { s ->
            assertNull(value(s.stage { it.readTrust() }).currentCompletedSnapshot())
            assertEquals(CompanyBusinessPublicationPhase.SIGNING_INTENT, value(s.stage { it.retained() }).single().phase)
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { it.recover(actionId) }))
        }
        session(seam) { s -> assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { it.review(actionId) })) }
    }

    @Test fun serialIntentSettlesOnlyItsExactRetainedNextAndReservesCompanyCompletionCapacity() {
        val seam = founded()
        val work = seam.openWork()
        try {
            val provisioner = SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)
            provisioner.issueOriginalPreparation(provisioner.prepareOriginalBranch("Synthetic Branch",
                JvmP256Primitives.rootFingerprint(seam.keys.rootBody())), "installer.one", "synthetic-installer-pass".toCharArray())
        } finally { seam.closeWork(work) }
        val previousBytes = session(seam) { s ->
            value(s.stage { it.prepare(proposal(s, CompanyBusinessPublicationKind.CATALOG_SKU_V1)) })
            checkNotNull(seam.businessPublicationBytes).copyOf()
        }
        val record = session(seam) { s -> value(s.stage {
            val company = checkNotNull(s.custody.companyOriginal())
            val state = checkNotNull(s.custody.companyState()).source as LocalCompanyAuthorityStateV2
            val allocation = state.original.allocations.single().body.coordinates
            // Deliberately inert P2 digest: this tests the ledger/marker cut, never a
            // complete target-history join or permission to publish a serial authority.
            val origin = LocalCompanySerialIssuerOriginV1(CompanyScope(allocation.scope.organizationId, allocation.scope.companyId),
                company.original.actionId, company.original.rootId, company.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId,
                PayloadDigest(JvmP256Primitives.documentDigest(state.original.root)), s.custody.rootFingerprint(),
                company.headAuthorityId, company.headEnrollmentId, company.original.issuerId,
                company.original.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId,
                PayloadDigest(JvmP256Primitives.documentDigest(state.original.delegation)), allocation.scope, allocation.allocationId,
                sha256("synthetic-inert-P2-digest".encodeToByteArray()))
            val proposal = makeNativeSerialAuthorityProposal(LocalPublishedSerialAuthorityV1(id(), id(), origin))
            val bytes = proposal.copyCanonicalContent()
            try { NativeCompanyBusinessPublicationRecordV1(id(), proposal.company, proposal.kind, proposal.publicationId,
                proposal.entityId, id(), SelfOwnedOctetsV1(bytes), proposal.contentDigest, CompanyBusinessPublicationPhase.PREPARED) }
            finally { bytes.fill(0) }
        }) }
        session(seam, failCompanionWrite = true) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.UNAVAILABLE, unavailable(s.stage {
                s.custody.retainBusinessPublications(checkNotNull(s.custody.readBusinessPublications()).next(record))
            }))
        }
        val nextBytes = checkNotNull(seam.businessPublicationBytes).copyOf()
        session(seam) { s ->
            assertEquals(OriginalLocalSerialCustodyPhaseV1.INTENT, value(s.stage { s.custody.readOriginalSerialCustody() }).phase)
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, unavailable(s.stage { s.custody.readBusinessPublications() }))
            assertEquals(SelfOwnedInstallationFailureV1.NATIVE_RETIRED,
                unavailable(s.stage { s.custody.recoverOriginalSerialCustody(record.originalActionId) }))
        }
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED,
                unavailable(s.stage { s.custody.recoverOriginalSerialCustody(id()) }))
        }
        seam.businessPublicationBytes = null
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
                unavailable(s.stage { s.custody.recoverOriginalSerialCustody(record.originalActionId) }))
        }
        seam.businessPublicationBytes = previousBytes
        session(seam) { s ->
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
                unavailable(s.stage { s.custody.recoverOriginalSerialCustody(record.originalActionId) }))
        }
        seam.businessPublicationBytes = nextBytes
        session(seam) { s ->
            val settled = value(s.stage { s.custody.recoverOriginalSerialCustody(record.originalActionId) })
            assertEquals(OriginalLocalSerialCustodyPhaseV1.SETTLED, settled.phase)
            assertEquals(record.originalActionId, settled.originalAuthorityActionId)
            assertEquals(record, value(s.stage { s.custody.readBusinessPublications() })?.records?.single { it.kind.isOriginalSerialKindV1() })
            assertEquals(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED, unavailable(s.stage {
                val state = checkNotNull(s.custody.companyState()).source as LocalCompanyAuthorityStateV2
                val exhausted = state.copy(original = state.original.copy(revision = Long.MAX_VALUE - 2L))
                SelfOwnedCompanyAuthorityV2.seal(checkNotNull(seam.store.companyBootstrapRecordV2()), exhausted, seam.keys,
                    seam.environment.bootCount)
            }))
        }
    }
}
