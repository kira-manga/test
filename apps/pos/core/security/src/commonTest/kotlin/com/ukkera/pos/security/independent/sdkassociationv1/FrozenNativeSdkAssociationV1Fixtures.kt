package com.ukkera.pos.security.independent.sdkassociationv1

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Selected unchanged inputs from qa/pos/freezes/native-sdk-association-v1/fixtures.json.
 * Synthetic DATA vectors only; UNBOUND values are retained as limitations, not encodings.
 */
internal object FrozenNativeSdkAssociationV1 {
    val data = Json.parseToJsonElement(FROZEN_DATA_FIXTURES).jsonObject
    val original = data.objectAt("v2_descriptor_original")
}

private const val FROZEN_DATA_FIXTURES: String = """
{
  "v2_descriptor_original": {
    "purpose": "EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_NATIVE_P256_SDK_X25519_PAIRING_V2",
    "domain": "original-native-device-sdk-identity-data/v2",
    "acquisition_id": "acquisition-a",
    "preparation_id": "preparation-a",
    "organization_id": "organization-a",
    "company_id": "company-a",
    "branch_id": "branch-a",
    "device_id": "native-device-a",
    "native_key_id": "native-key-id-a",
    "complete_native_target_admission_key_descriptor_ref": "original-complete-native-descriptor-a",
    "installed_application_facts_ref": "original-installed-application-facts-a",
    "sdk_app_id": "pos.app-a",
    "sdk_peer_id": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq",
    "sdk_fingerprint": "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "sdk_pairing_qr": "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "maximum_whole_descriptor_bytes": 16384,
    "required_reencoding": "exact retained original bytes and digest; every captured field included",
    "whole_original_encoded_bytes": "UNBOUND; complete public nested layout required",
    "whole_original_descriptor_digest": "UNBOUND; public digest profile/value required",
    "actual_kit_correspondence": "not asserted by synthetic syntax vector",
    "expected_public_decode": "exact DATA only; no trust or admission"
  },
  "v2_original_fact_variants": {
    "one_fact_changed_per_run": [
      "purpose",
      "domain",
      "acquisition_id",
      "preparation_id",
      "organization_id",
      "company_id",
      "branch_id",
      "device_id",
      "native_key_id",
      "complete_native_target_admission_key_descriptor",
      "installed_application_facts",
      "sdk_app_id",
      "sdk_peer_id",
      "sdk_fingerprint",
      "sdk_pairing_qr"
    ],
    "unchanged_original_decode_reencode": "exact original bytes and digest preserved",
    "changed_fact_expectation": "different DATA or rejected invalid profile; never normalized to original bytes/digested subject",
    "nested_native_and_installation_per_fact_expansion": "UNBOUND; do not invent complete native envelope or installed-fact layout"
  },
  "v2_distinct_id_variants": {
    "baseline_ids": {
      "acquisition_id": "acquisition-a",
      "preparation_id": "preparation-a",
      "organization_id": "organization-a",
      "company_id": "company-a",
      "branch_id": "branch-a",
      "device_id": "native-device-a",
      "native_key_id": "native-key-id-a"
    },
    "unordered_pair_count": 21,
    "collisions": [
      {
        "left_field": "acquisition_id",
        "right_field": "preparation_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "acquisition_id",
        "right_field": "organization_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "acquisition_id",
        "right_field": "company_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "acquisition_id",
        "right_field": "branch_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "acquisition_id",
        "right_field": "device_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "acquisition_id",
        "right_field": "native_key_id",
        "collision_value": "acquisition-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "preparation_id",
        "right_field": "organization_id",
        "collision_value": "preparation-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "preparation_id",
        "right_field": "company_id",
        "collision_value": "preparation-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "preparation_id",
        "right_field": "branch_id",
        "collision_value": "preparation-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "preparation_id",
        "right_field": "device_id",
        "collision_value": "preparation-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "preparation_id",
        "right_field": "native_key_id",
        "collision_value": "preparation-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "organization_id",
        "right_field": "company_id",
        "collision_value": "organization-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "organization_id",
        "right_field": "branch_id",
        "collision_value": "organization-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "organization_id",
        "right_field": "device_id",
        "collision_value": "organization-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "organization_id",
        "right_field": "native_key_id",
        "collision_value": "organization-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "company_id",
        "right_field": "branch_id",
        "collision_value": "company-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "company_id",
        "right_field": "device_id",
        "collision_value": "company-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "company_id",
        "right_field": "native_key_id",
        "collision_value": "company-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "branch_id",
        "right_field": "device_id",
        "collision_value": "branch-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "branch_id",
        "right_field": "native_key_id",
        "collision_value": "branch-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      },
      {
        "left_field": "device_id",
        "right_field": "native_key_id",
        "collision_value": "native-device-a",
        "action": "set right field equal to original left field; all other values unchanged",
        "expected": "reject V2 descriptor"
      }
    ]
  },
  "v2_descriptor_size_variants": [
    {
      "whole_encoded_byte_count": 16384,
      "all_other_profile_rules_satisfied": true,
      "expected": "accept DATA only",
      "exact_whole_input": "UNBOUND; legal field content required, no trailing filler"
    },
    {
      "whole_encoded_byte_count": 16385,
      "expected": "reject; do not truncate or grant trust",
      "exact_whole_input": "UNBOUND; whole public descriptor layout required"
    }
  ],
  "v2_app_id_variants": [
    {
      "id": "ascii_255",
      "value": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "utf8_bytes": 255,
      "expected": "accept exact DATA"
    },
    {
      "id": "unicode_255",
      "value": "\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9a",
      "utf8_bytes": 255,
      "expected": "accept exact DATA"
    },
    {
      "id": "ascii_256",
      "value": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "utf8_bytes": 256,
      "expected": "reject"
    },
    {
      "id": "unicode_256",
      "value": "\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9",
      "utf8_bytes": 256,
      "expected": "reject"
    },
    {
      "id": "empty",
      "value": "",
      "utf8_bytes": 0,
      "expected": "reject blank"
    },
    {
      "id": "spaces_only",
      "value": "   ",
      "utf8_bytes": 3,
      "expected": "reject blank"
    },
    {
      "id": "tab_only",
      "value": "\t",
      "utf8_bytes": 1,
      "expected": "reject blank"
    },
    {
      "id": "nonblank_spaces",
      "value": " app-a ",
      "utf8_bytes": 7,
      "expected": "accept exact DATA without trimming"
    },
    {
      "id": "composed",
      "value": "\u00e9",
      "utf8_bytes": 2,
      "expected": "accept exact DATA distinct from decomposed"
    },
    {
      "id": "decomposed",
      "value": "e\u0301",
      "utf8_bytes": 3,
      "expected": "accept exact DATA distinct from composed"
    }
  ],
  "v2_peer_id_vectors": {
    "canonical": [
      {
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq",
        "raw_32_bytes_hex": "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        "base32_characters": 52,
        "text_ascii_bytes": 58,
        "expected": "accept exact DATA"
      },
      {
        "text": "p2id2-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "raw_32_bytes_hex": "0000000000000000000000000000000000000000000000000000000000000000",
        "base32_characters": 52,
        "text_ascii_bytes": 58,
        "expected": "accept exact DATA; authenticates no actual Kit"
      }
    ],
    "invalid": [
      {
        "id": "different_version",
        "text": "p2id1-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "prefix_absent",
        "text": "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "uppercase_prefix",
        "text": "P2ID2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "uppercase_base32",
        "text": "p2id2-AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYPQ"
      },
      {
        "id": "padded",
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq===="
      },
      {
        "id": "payload_51",
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp"
      },
      {
        "id": "payload_53",
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypqa"
      },
      {
        "id": "invalid_base32_0",
        "text": "p2id2-0aaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "invalid_base32_8",
        "text": "p2id2-8aaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "wrong_separator",
        "text": "p2id2:aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "leading_space",
        "text": " p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
      },
      {
        "id": "trailing_space",
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq "
      },
      {
        "id": "nonzero_residual_bits",
        "text": "p2id2-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypr",
        "same_decoded_32_bytes_if_residual_bits_are_discarded": true
      }
    ],
    "invalid_expected": "reject; no silent normalization",
    "last_base32_character_with_zero_residual_bits": [
      "a",
      "q"
    ]
  },
  "v2_fingerprint_qr_variants": {
    "canonical_syntax_only": {
      "fingerprint": "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "app_binding": "p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "qr": "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "digest_payload_characters": 52,
      "digest_bytes": 32,
      "fingerprint_ascii_bytes": 57,
      "qr_ascii_bytes": 125,
      "expected": "accept DATA syntax/equality only; authenticates no peer"
    },
    "noncanonical_fingerprints": [
      "p2f2-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "P2F1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2f1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=",
      "p2f1-0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab",
      " p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "
    ],
    "noncanonical_qrs": [
      "p2pkit:v1:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "P2PKIT:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit/v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit:v2:p2a2-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit:v2:p2a1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab",
      "p2pkit:v2::p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:",
      "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:extra",
      "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ",
      "p2pkit%3Av2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ],
    "canonical_qr_fingerprint_mismatch": {
      "descriptor_fingerprint": "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "qr": "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq",
      "qr_fingerprint": "p2f1-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq",
      "expected": "reject exact fingerprint inequality"
    },
    "canonical_changed_app_binding_qr": {
      "qr": "p2pkit:v2:p2a1-aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "expected": "different canonical DATA; actual Kit correspondence not established"
    },
    "noncanonical_expected": "reject without normalization",
    "app_id_binding_derivation": "UNSPECIFIED; no hash/binding algorithm inferred"
  },
  "v2_invalid_encoding_variants": {
    "malformed_utf8_hex": [
      "80",
      "c3",
      "c328",
      "c0af",
      "eda080",
      "f4908080",
      "ff"
    ],
    "malformed_utf8_placement": "required text field in an otherwise canonical whole descriptor; layout UNBOUND",
    "trailing_byte_hex": [
      "00",
      "ff"
    ],
    "wrong_purposes": [
      "EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_NATIVE_P256_SDK_X25519_PAIRING_V1",
      "explicit_named_company_head_one_new_device_native_p256_sdk_x25519_pairing_v2",
      "EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_NATIVE_P256_SDK_X25519_PAIRING_V2 "
    ],
    "wrong_domains": [
      "original-native-device-sdk-identity-data/v1",
      "original-native-device-sdk-identity-data/v2 ",
      "ORIGINAL-NATIVE-DEVICE-SDK-IDENTITY-DATA/V2"
    ],
    "other_noncanonical_native_field_encoding": "UNBOUND; no nested layout inferred",
    "expected": "reject invalid V2 profile; no silent normalization to original"
  },
  "v2_returned_buffer_variants": {
    "actions": [
      "overwrite first returned byte",
      "overwrite last returned byte",
      "overwrite every returned byte"
    ],
    "views": "each supported public returned byte buffer belonging to descriptor state",
    "repeat_views": [
      "subsequent field read",
      "subsequent encoding",
      "after public decoding",
      "after recovery where available"
    ],
    "expected_retained_facts": "exact original values unchanged",
    "expected_subsequent_bytes": "exact original bytes unchanged",
    "expected_original_digest": "exact original digest unchanged",
    "array_reference_identity_assertion": false,
    "whole_encoded_byte_fixture": "UNBOUND until public nested layout supplied"
  }
}
"""
