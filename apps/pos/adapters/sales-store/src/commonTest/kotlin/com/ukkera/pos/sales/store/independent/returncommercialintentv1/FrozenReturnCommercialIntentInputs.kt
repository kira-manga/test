package com.ukkera.pos.sales.store.independent.returncommercialintentv1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unchanged semantic variant projection from frozen return-commercial-intent-v1.
 * Manifest SHA-256: 78c9a9b1d53123d0d39e23de76b86fbc403087f6078f78ea782343871f8a4e78.
 * These are field/default comparisons, not full canonical request/result goldens
 * or genuine staged operations, source/history records or protected authority.
 * RS-NON-PENDING has no assigned fixture and remains unbound. Raw noncanonical
 * false/default-null admission is not established by typed serialization slices.
 */
internal object FrozenReturnCommercialIntentInputs {
    private const val VARIANTS_JSON: String = """
    {
      "request_variants": [
        {
          "id": "RQ-OMITTED",
          "assigned_field": "recordOriginalCommercialTiming",
          "presence": "omitted",
          "semantic_value": false,
          "canonical_expectation": "exact existing legacy bytes; field omitted"
        },
        {
          "id": "RQ-FALSE",
          "assigned_field": "recordOriginalCommercialTiming",
          "presence": "explicit",
          "literal_value": false,
          "canonical_expectation": "equal to RQ-OMITTED; field omitted"
        },
        {
          "id": "RQ-TRUE",
          "assigned_field": "recordOriginalCommercialTiming",
          "presence": "explicit",
          "literal_value": true,
          "canonical_expectation": "distinguishable original intent; literal true retained"
        }
      ],
      "projection_variants": [
        {
          "id": "RS-ABSENT",
          "assigned_field": "originalCommercialTiming",
          "presence": "unset",
          "profiles": [
            "legacy assessment",
            "legacy protected projection"
          ],
          "expectation": "field omitted; legacy absent-profile bytes retained"
        },
        {
          "id": "RS-NULL",
          "assigned_field": "originalCommercialTiming",
          "value": null,
          "profiles": [
            "legacy assessment",
            "legacy protected projection"
          ],
          "expectation": "field omitted; same default canonical profile as RS-ABSENT"
        },
        {
          "id": "RS-NON-PENDING",
          "profile": "Existing documented current non-PENDING assessment/projection, assigned at binding",
          "expectation": "no populated originalCommercialTiming; preserve authorized historical PENDING evidence"
        }
      ]
    }
    """

    private val root: JsonObject = Json.parseToJsonElement(VARIANTS_JSON).jsonObject
    private val requests: Map<String, JsonObject> = root.getValue("request_variants").jsonArray
        .map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
    private val projections: Map<String, JsonObject> = root.getValue("projection_variants").jsonArray
        .map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }

    fun request(id: String): JsonObject = requests.getValue(id)
    fun projection(id: String): JsonObject = projections.getValue(id)
}
