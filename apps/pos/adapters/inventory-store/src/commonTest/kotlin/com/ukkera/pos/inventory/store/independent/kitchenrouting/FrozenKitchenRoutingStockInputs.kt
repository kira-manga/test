package com.ukkera.pos.inventory.store.independent.kitchenrouting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Unchanged selected inputs from qa/pos/freezes/kitchen-station-routing-inputs-v1. */
internal fun frozenRoutingStockInputs(): JsonObject = Json.parseToJsonElement(
    """
{
  "ids": {
    "stationA": "50000000-0000-4000-8000-000000000001",
    "stationB": "50000000-0000-4000-8000-000000000002"
  },
  "stationReadKey": {
    "name": "stock.normalized.station-profile",
    "version": 2
  },
  "stationRequests": {
    "stationA": "{\"stationId\":\"50000000-0000-4000-8000-000000000001\"}",
    "stationB": "{\"stationId\":\"50000000-0000-4000-8000-000000000002\"}",
    "encoding": "UTF-8",
    "invalid": [
      {
        "id": "empty-object",
        "json": "{}"
      },
      {
        "id": "null",
        "json": "null"
      },
      {
        "id": "array",
        "json": "[]"
      },
      {
        "id": "null-station",
        "json": "{\"stationId\":null}"
      },
      {
        "id": "numeric-station",
        "json": "{\"stationId\":7}"
      },
      {
        "id": "empty-id",
        "json": "{\"stationId\":\"\"}"
      },
      {
        "id": "non-uuid",
        "json": "{\"stationId\":\"not-a-uuid\"}"
      },
      {
        "id": "extra-field",
        "json": "{\"stationId\":\"50000000-0000-4000-8000-000000000001\",\"extra\":1}"
      },
      {
        "id": "duplicate-identical",
        "json": "{\"stationId\":\"50000000-0000-4000-8000-000000000001\",\"stationId\":\"50000000-0000-4000-8000-000000000001\"}"
      },
      {
        "id": "duplicate-different",
        "json": "{\"stationId\":\"50000000-0000-4000-8000-000000000001\",\"stationId\":\"50000000-0000-4000-8000-000000000002\"}"
      },
      {
        "id": "malformed",
        "json": "{\"stationId\":"
      }
    ],
    "invalidDomainIdScalars": [
      "",
      " ",
      "not-a-uuid"
    ]
  },
  "profileResponses": {
    "qualifiedLegacy": "{\"version\":2,\"normalized\":false}",
    "qualifiedNormalized": "{\"version\":2,\"normalized\":true}",
    "invalid": [
      {
        "id": "version-1",
        "json": "{\"version\":1,\"normalized\":false}"
      },
      {
        "id": "version-3",
        "json": "{\"version\":3,\"normalized\":true}"
      },
      {
        "id": "missing-version",
        "json": "{\"normalized\":false}"
      },
      {
        "id": "missing-normalized",
        "json": "{\"version\":2}"
      },
      {
        "id": "null-normalized",
        "json": "{\"version\":2,\"normalized\":null}"
      },
      {
        "id": "string-normalized",
        "json": "{\"version\":2,\"normalized\":\"false\"}"
      },
      {
        "id": "number-normalized",
        "json": "{\"version\":2,\"normalized\":0}"
      },
      {
        "id": "non-object",
        "json": "[]"
      },
      {
        "id": "malformed",
        "json": "{\"version\":2,\"normalized\":"
      }
    ]
  }
}
    """.trimIndent(),
).jsonObject
