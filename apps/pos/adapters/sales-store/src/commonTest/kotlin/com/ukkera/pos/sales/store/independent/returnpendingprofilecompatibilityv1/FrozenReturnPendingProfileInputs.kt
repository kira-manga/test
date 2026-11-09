package com.ukkera.pos.sales.store.independent.returnpendingprofilecompatibilityv1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unchanged frozen logical inputs; opaque genuine producer anchors remain unbound. */
internal object FrozenReturnPendingProfileInputs {
    // Source fixture SHA-256: 26e5937b9484f666d12d5db1fca31239b2ba9d59677a6f328e67273be6f2cffc.
    private val root = Json.parseToJsonElement(FIXTURES_JSON).jsonObject
    val scope: JsonObject get() = root.getValue("scope").jsonObject
    val sources: JsonObject get() = root.getValue("sourceFactsForEachSourceBoundOriginal").jsonObject
    val current: JsonObject get() = root.getValue("newerCurrentFacts").jsonObject
    val foreign: JsonObject get() = root.getValue("foreignReplacementFacts").jsonObject
    fun original(id: String): JsonObject = root.getValue("originals").jsonArray
        .map { it.jsonObject }.single { it.text("logicalId") == id }

    private const val FIXTURES_JSON: String = """
{
  "profile": "return-pending-profile-compatibility",
  "version": 1,
  "status": "NOT RUN",
  "fixtureKind": "logical requirements fixtures; not production wire messages",
  "scope": {
    "organization": "21000000-0000-4000-8000-000000000001",
    "company": "21000000-0000-4000-8000-000000000002",
    "branch": "21000000-0000-4000-8000-000000000003",
    "originalActor": "21000000-0000-4000-8000-000000000004",
    "differentActor": "21000000-0000-4000-8000-000000000005"
  },
  "originals": [
    {
      "logicalId": "L",
      "operation": "21000000-0000-4000-8000-000000000040",
      "sale": "21000000-0000-4000-8000-000000000010",
      "portion": "21000000-0000-4000-8000-000000000011",
      "requiredGenuineProfile": "PENDING; no qualified source references; timing intent omitted; no optional timing observation",
      "expectedReplay": "exact original canonical bytes and history anchors; no enrichment"
    },
    {
      "logicalId": "S0",
      "operation": "21000000-0000-4000-8000-000000000041",
      "sale": "21000000-0000-4000-8000-000000000012",
      "portion": "21000000-0000-4000-8000-000000000013",
      "requiredGenuineProfile": "PENDING; exact qualified source references; timing intent omitted; no optional timing observation",
      "expectedReplay": "exact original canonical bytes/history and required authentic source/provenance facts; no legacy fallback"
    },
    {
      "logicalId": "S1",
      "operation": "21000000-0000-4000-8000-000000000042",
      "sale": "21000000-0000-4000-8000-000000000014",
      "portion": "21000000-0000-4000-8000-000000000015",
      "requiredGenuineProfile": "PENDING; exact qualified source references; explicit recordOriginalCommercialTiming true; retained original observation",
      "expectedReplay": "exact original canonical bytes/history and required authentic source/provenance facts; no legacy fallback"
    }
  ],
  "sourceFactsForEachSourceBoundOriginal": {
    "originalUnit": "each",
    "originalPrecision": 0,
    "soldQuantity": "2",
    "paidMinorEGP": "12000",
    "paidFinalizeOperationS0": "21000000-0000-4000-8000-000000000070",
    "paidFinalizeOperationS1": "21000000-0000-4000-8000-000000000071",
    "paidFinalizeAt": "2026-10-01T07:55:00Z",
    "capturedPromisePolicy": "21000000-0000-4000-8000-000000000060",
    "capturedPromiseRevision": "0",
    "capturedCategoryCode": "CLOTHING",
    "customerReceiptQuantity": "2",
    "customerReceiptHeadRevision": "1",
    "customerReceiptEventCount": 1,
    "customerReceiptAt": "2026-10-01T08:00:00Z",
    "customerReceiptZone": "Africa/Cairo",
    "customerReceiptRecordedAt": "2026-10-01T08:01:00Z",
    "receiptEventS0": "21000000-0000-4000-8000-000000000020",
    "receiptEventS1": "21000000-0000-4000-8000-000000000021",
    "intakeOperationS0": "21000000-0000-4000-8000-000000000030",
    "intakeOperationS1": "21000000-0000-4000-8000-000000000031",
    "originalMasterSubmissionAt": "2026-10-22T08:00:00Z",
    "initialQualificationProcessingAt": "2026-10-23T10:00:00Z",
    "capturedOrdinaryCommercialDays": 21,
    "elapsedHours": 504,
    "elapsedSeconds": 1814400,
    "exactInclusiveExpiry": "2026-10-22T08:00:00Z",
    "temporalComparisonForS1": "WITHIN",
    "rightsPolicy": "21000000-0000-4000-8000-000000000050",
    "retainedRightsPublication": "21000000-0000-4000-8000-000000000051",
    "retainedRightsActivationOperation": "21000000-0000-4000-8000-000000000052",
    "retainedCompanyPolicyRevision": "0",
    "retainedActiveStorageRevision": "1",
    "retainedApplicableChangeOfMindDays": 14,
    "sourceAuthenticity": "actual original paid sale/captured promise/complete receipt and genuine native signed rights with exact retained producers; UUIDs/digest syntax alone are insufficient"
  },
  "newerCurrentFacts": {
    "currentOrdinaryCommercialDays": 14,
    "rightsPolicy": "21000000-0000-4000-8000-000000000050",
    "rightsPublication": "21000000-0000-4000-8000-000000000053",
    "companyPolicyRevision": "1",
    "activeStorageRevision": "2",
    "applicableChangeOfMindDays": 30,
    "laterProcessingAt": "2026-11-03T00:00:00Z",
    "legacyEvidenceCondition": "genuine newer compatible sources available for L; original L result remains untouched"
  },
  "foreignReplacementFacts": {
    "company": "21000000-0000-4000-8000-000000000102",
    "branch": "21000000-0000-4000-8000-000000000103",
    "sale": "21000000-0000-4000-8000-000000000114",
    "portion": "21000000-0000-4000-8000-000000000115",
    "receiptEvent": "21000000-0000-4000-8000-000000000121",
    "rightsPublication": "21000000-0000-4000-8000-000000000151",
    "dates": "same plausible dates as sourceFacts; foreign identity does not authorize substitution"
  },
  "noEffectBaselinePerIndependentOriginal": {
    "stockOnHand": "18",
    "stockReserved": "0",
    "stockQuarantined": "0",
    "inventoryValueMinorEGP": "45000",
    "originalSoldQuantity": "2",
    "originalPaidMinorEGP": "12000",
    "remainingReturnQuantity": "2",
    "remainingRefundableMinorEGP": "12000",
    "physicalCashMinorEGP": "50000",
    "refundDueMinorEGP": "0",
    "refundPaidMinorEGP": "0",
    "acceptedReturnCount": 0,
    "originalQualificationResultCount": 1,
    "additionalQualificationCommitsOnRecovery": 0,
    "additionalReceiptEventsOnRecovery": 0,
    "additionalPolicyActivationsOnRecovery": 0,
    "newSessionOrAuthorityGrantFromHistory": 0,
    "expected": "exact before/after equality, including independent original balances/records not merely response labels"
  },
  "opaqueAnchorRequirements": [
    "genuine original canonical staged business request bytes",
    "genuine original canonical durable result bytes and its supported format",
    "original operation and actor attribution",
    "exact original resource identities and revisions",
    "exact retained dependency/source identities, order and producer facts",
    "exact original before-state and ordered writes",
    "original intake operation/submission time",
    "original optional observation presence/absence and exact bytes",
    "original audit/journal/outbox identity and immutable content"
  ],
  "unresolvedMappings": [
    "existing QUALIFY public canonical request/result/profile encoding",
    "exact qualification recovery refusal wire code",
    "public projection/producer mapping of dependencies, before-state, writes, resources and revisions",
    "historical writer versions and provisioning steps producing genuine L/S0/S1 anchors",
    "historical explicitly false optional intent accepted by an applicable supported writer"
  ]
}
    """
}

internal fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content
