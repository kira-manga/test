package com.ukkera.pos.app.gateway.independent.kitchenhints

import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Exact additive constructor-only input document. No new cases or expected outcomes.
 * FREEZE.json SHA-256: 7d6e6f28c3787ee7ea1ad993d40db6c5c191e889d2e40f32dc11517334b33ce1.
 * These coordinates are neither business UOM nor a runtime clock/expiry/authority observation.
 */

// MATRIX-FIXTURE-BINDING.v0.3.1.json SHA-256: 65d0977a7d8db75b3eeef651c0c7bb093a0f9ab2a16c689cee717a4d3de3b753.
internal const val FROZEN_KITCHEN_HINT_COORDINATES: String = """{
  "version": "0.3.1-candidate-representation-note",
  "status": "UNFROZEN_HASH_SEALED_NOTE_NOT_RUN",
  "purpose": "Additive constructor-only coordinates for the existing standalone station display/input matrix. No new cases, oracles, actions, production authority or automatic wall-time conversion.",
  "retainedMatrix": {
    "fixture": "MATRIX-FIXTURES.v0.3.json",
    "fixtureSHA256": "f8a6917eeaf844a93a54ac0e5999afa4f1676761b1d011c7f2bec1df55493df3",
    "specification": "MATRIX-SPEC.v0.3.md",
    "specificationSHA256": "8675c20a92792b8d6663e891e58997242bb80ffb77a65fcb34c4603d86065ef1",
    "matrix": "ASSIGNMENT-ACTION-MATRIX.v0.3.json",
    "matrixSHA256": "678f8db73d3ed2493aaac8f79fe7c46026193d90483e975446ff74b1306eb2f4",
    "manifest": "MATRIX-MANIFEST.v0.3.json",
    "manifestSHA256": "b00a7ac6ace107beb1040290de9a2386872413ee703f8c78deadea78a9443cf4",
    "rule": "Every retained byte, literal quantity, ISO label, finite 33-row case set, 12 assertions and zero-operation window remains unchanged."
  },
  "publicRepresentationConstraints": {
    "Quantity": {
      "unitId": "DomainId",
      "scaled": "Long",
      "scale": "Int in 0..9",
      "representedQuantity": "scaled / 10^scale"
    },
    "monotonicCoordinate": {
      "bootId": "DomainId",
      "elapsedMillis": "Nonnegative",
      "noWallTimeConversion": true
    }
  },
  "quantityCoordinates": {
    "unitId": "75400000-0000-4000-8000-000000000001",
    "scaled": 100,
    "scale": 2
  },
  "quantityLinks": [
    {
      "taskObservationRef": "HOT_CURRENT",
      "unchangedPublishedQuantity": 1,
      "constructorOnlyQuantity": {
        "unitId": "75400000-0000-4000-8000-000000000001",
        "scaled": 100,
        "scale": 2
      },
      "semanticNotice": "The opaque synthetic unit ID supplies the declared DomainId input only. It does not establish the business PORTION UOM, compatible conversions, scale precision policy or ingredient/stock handling."
    },
    {
      "taskObservationRef": "PASS_CURRENT",
      "unchangedPublishedQuantity": 1,
      "constructorOnlyQuantity": {
        "unitId": "75400000-0000-4000-8000-000000000001",
        "scaled": 100,
        "scale": 2
      },
      "semanticNotice": "The opaque synthetic unit ID supplies the declared DomainId input only. It does not establish the business PORTION UOM, compatible conversions, scale precision policy or ingredient/stock handling."
    },
    {
      "taskObservationRef": "HOT_RENEWED_CURRENT",
      "unchangedPublishedQuantity": 1,
      "constructorOnlyQuantity": {
        "unitId": "75400000-0000-4000-8000-000000000001",
        "scaled": 100,
        "scale": 2
      },
      "semanticNotice": "The opaque synthetic unit ID supplies the declared DomainId input only. It does not establish the business PORTION UOM, compatible conversions, scale precision policy or ingredient/stock handling."
    }
  ],
  "quantityArithmetic": "100 / 10^2 = 1 exactly, preserving the literal published quantity=1. Scale 2 is an independent constructor input, not a sold-stock UOM/rounding or currency scale rule.",
  "monotonicCoordinates": {
    "observation": {
      "bootId": "76400000-0000-4000-8000-000000000001",
      "elapsedMillis": 40000
    },
    "accessExpiry": {
      "bootId": "76400000-0000-4000-8000-000000000001",
      "elapsedMillis": 120000
    },
    "workExpiry": {
      "bootId": "76400000-0000-4000-8000-000000000001",
      "elapsedMillis": 240000
    }
  },
  "displaySessionLinks": [
    {
      "displaySessionRef": "CURRENT_HOT",
      "unchangedISOLabels": {
        "observationTime": "2026-10-29T12:00:00Z",
        "accessExpiry": "2026-10-29T13:00:00Z",
        "workExpiry": "2026-10-29T20:00:00Z"
      },
      "constructorOnlyCoordinates": {
        "observation": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 40000
        },
        "accessExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 120000
        },
        "workExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 240000
        }
      },
      "relationNotice": "These independently chosen same-boot numerical inputs are not wall-time conversions, a runtime clock sample, an issued session, a verified expiry rule or current authority. Every row is independently evaluated; no auth/renewal/boot action occurs."
    },
    {
      "displaySessionRef": "CURRENT_PASS",
      "unchangedISOLabels": {
        "observationTime": "2026-10-29T12:00:00Z",
        "accessExpiry": "2026-10-29T13:00:00Z",
        "workExpiry": "2026-10-29T20:00:00Z"
      },
      "constructorOnlyCoordinates": {
        "observation": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 40000
        },
        "accessExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 120000
        },
        "workExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 240000
        }
      },
      "relationNotice": "These independently chosen same-boot numerical inputs are not wall-time conversions, a runtime clock sample, an issued session, a verified expiry rule or current authority. Every row is independently evaluated; no auth/renewal/boot action occurs."
    },
    {
      "displaySessionRef": "CURRENT_SECOND",
      "unchangedISOLabels": {
        "observationTime": "2026-10-29T12:00:00Z",
        "accessExpiry": "2026-10-29T13:00:00Z",
        "workExpiry": "2026-10-29T20:00:00Z"
      },
      "constructorOnlyCoordinates": {
        "observation": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 40000
        },
        "accessExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 120000
        },
        "workExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 240000
        }
      },
      "relationNotice": "These independently chosen same-boot numerical inputs are not wall-time conversions, a runtime clock sample, an issued session, a verified expiry rule or current authority. Every row is independently evaluated; no auth/renewal/boot action occurs."
    },
    {
      "displaySessionRef": "CURRENT_FOREIGN",
      "unchangedISOLabels": {
        "observationTime": "2026-10-29T12:00:00Z",
        "accessExpiry": "2026-10-29T13:00:00Z",
        "workExpiry": "2026-10-29T20:00:00Z"
      },
      "constructorOnlyCoordinates": {
        "observation": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 40000
        },
        "accessExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 120000
        },
        "workExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 240000
        }
      },
      "relationNotice": "These independently chosen same-boot numerical inputs are not wall-time conversions, a runtime clock sample, an issued session, a verified expiry rule or current authority. Every row is independently evaluated; no auth/renewal/boot action occurs."
    },
    {
      "displaySessionRef": "RENEWED_HOT",
      "unchangedISOLabels": {
        "observationTime": "2026-10-29T12:00:00Z",
        "accessExpiry": "2026-10-29T13:00:00Z",
        "workExpiry": "2026-10-29T20:00:00Z"
      },
      "constructorOnlyCoordinates": {
        "observation": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 40000
        },
        "accessExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 120000
        },
        "workExpiry": {
          "bootId": "76400000-0000-4000-8000-000000000001",
          "elapsedMillis": 240000
        }
      },
      "relationNotice": "These independently chosen same-boot numerical inputs are not wall-time conversions, a runtime clock sample, an issued session, a verified expiry rule or current authority. Every row is independently evaluated; no auth/renewal/boot action occurs."
    }
  ],
  "monotonicArithmetic": "40,000 / 120,000 / 240,000 are independently chosen finite nonnegative same-synthetic-boot inputs. No equation or epoch maps them to the unchanged ISO labels.",
  "newCases": 0,
  "changedExpectedOutcomes": 0,
  "newReadsAuthStageSubmitLookupAcknowledgmentsOrBusinessOperations": 0,
  "runtimeExecutionStatus": "NOT_RUN",
  "actualCurrentAuthorityEstablished": false,
  "unverifiedSemantics": [
    "Business UOM identity and compatibility, quantity scale policy, stock/unit conversions",
    "Real current OS/runtime boot or monotonic clock, ISO-to-monotonic conversion, actual expiry/current-session semantics",
    "Admitted device/session/employee/current Master policy and authoritative permission decisions",
    "Production persistence/RPC/native/physical/owner acceptance and all broader body/witness/business requirements"
  ],
  "doNotUseAsAuthority": "These constructor inputs may wire only the reviewed standalone display/input checks. They cannot create a production session/admitted principal, prove nonexpiry, convert wall-time labels automatically or change any matrix expected result/operation count. Any actual authority/expiry/UOM test requires a separate genuine binding and evidence."
}
"""


internal data class FrozenKitchenDisplayCoordinates(
    internal val originalLink: JsonObject,
    internal val observation: MonotonicMoment,
    internal val accessExpiry: MonotonicMoment,
    internal val workExpiry: MonotonicMoment,
)

/** No wall-time conversion, quantity rescaling, expiry interpretation, or replacement coordinates. */
internal object FrozenKitchenHintCoordinates {
    private val inputs: JsonObject = Json.parseToJsonElement(FROZEN_KITCHEN_HINT_COORDINATES).jsonObject

    internal fun display(row: JsonObject): FrozenKitchenDisplayCoordinates {
        val displayRef = row.obj("input").text("sessionViewRef")
        val link = inputs.array("displaySessionLinks").map { it.jsonObject }
            .single { it.text("displaySessionRef") == displayRef }
        val retained = FrozenKitchenHintInputs.session(row)
        val labels = link.obj("unchangedISOLabels")
        listOf("observationTime", "accessExpiry", "workExpiry").forEach { key ->
            require(retained.text(key) == labels.text(key)) {
                "Additive coordinates do not match the retained literal label: $displayRef/$key"
            }
        }
        val coordinates = link.obj("constructorOnlyCoordinates")
        return FrozenKitchenDisplayCoordinates(
            originalLink = link,
            observation = coordinate(coordinates.obj("observation")),
            accessExpiry = coordinate(coordinates.obj("accessExpiry")),
            workExpiry = coordinate(coordinates.obj("workExpiry")),
        )
    }

    internal fun quantity(row: JsonObject): Quantity {
        val observationRef = row.obj("input").text("taskObservationRef")
        val link = inputs.array("quantityLinks").map { it.jsonObject }
            .single { it.text("taskObservationRef") == observationRef }
        val retainedTask = FrozenKitchenHintInputs.observation(row).obj("task")
        require(retainedTask.long("quantity") == link.long("unchangedPublishedQuantity")) {
            "Additive coordinates do not match the retained literal quantity: $observationRef"
        }
        val coordinates = link.obj("constructorOnlyQuantity")
        return Quantity(
            unitId = DomainId(coordinates.text("unitId")),
            scaled = coordinates.long("scaled"),
            scale = coordinates.long("scale").toInt(),
        )
    }

    private fun coordinate(input: JsonObject): MonotonicMoment = MonotonicMoment(
        bootId = DomainId(input.text("bootId")),
        elapsedMillis = input.long("elapsedMillis"),
    )
}
