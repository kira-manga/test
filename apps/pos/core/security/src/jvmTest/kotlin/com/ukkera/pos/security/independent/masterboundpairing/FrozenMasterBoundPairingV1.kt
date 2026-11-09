package com.ukkera.pos.security.independent.masterboundpairing

/**
 * Selected unchanged DATA inputs and numeric expectations from
 * qa/pos/designs/master-bound-device-pairing/fixtures-v1.json, version 1.0.0.
 * F-SCOPE is the supplied synthetic DATA fixture. No genuine M1/D1/C1/P1/A1/R1/E1
 * bytes, native signatures or custody are supplied by that freeze.
 */
internal object FrozenMasterBoundPairingV1 {
    const val ORGANIZATION_UUID = "11111111-1111-4111-8111-111111111111"
    const val COMPANY_UUID = "22222222-2222-4222-8222-222222222222"
    const val BRANCH_UUID = "33333333-3333-4333-8333-333333333333"
    const val MASTER_DEVICE_UUID = "44444444-4444-4444-8444-444444444444"
    const val TARGET_DEVICE_UUID = "55555555-5555-4555-8555-555555555555"

    const val BRANCH_APP_ID = "ukkera-pos.rpc.branch.v1:11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222:33333333-3333-4333-8333-333333333333"
    const val BRANCH_APP_ID_UTF8_BYTES = 135
    const val INVALID_DEVICE_SUFFIXED_APP_ID = "ukkera-pos.rpc.branch.v1:11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222:33333333-3333-4333-8333-333333333333:55555555-5555-4555-8555-555555555555"
    const val INVALID_DEVICE_SUFFIXED_APP_ID_UTF8_BYTES = 172
    const val INITIAL_MASTER_NAME = "Master 44444444-4444-4444-8444-444444444444"
    const val INITIAL_MASTER_NAME_UTF8_BYTES = 43

    const val MASTER_ORIGINAL_MAX_BYTES = 16384
    const val APP_ID_AND_NAME_MAX_UTF8_BYTES = 255
    const val RESERVED_ALLOCATION_IDENTIFIER_COUNT = 12
    const val TRANSFER_FRAMING_ALLOWANCE_BYTES = 1024
}
