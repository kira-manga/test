package com.ukkera.pos.database

import com.ukkera.pos.security.*

/** Borrowed only by the actual authenticated administration read frame. Its caller enforces
 * zero writes and revalidates the current operator/peer through confirmed read completion.
 * This routine neither acquires a writer nor prepares an employee/head/native capability.
 * The exact transaction/census comparison is retired before this DATA response returns. */
internal suspend fun reviewLocalEmployeeCreationInputs(transaction: RoomLocalSecurityTransaction,
    principal: LocalEmployeePrincipal, original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 =
    RoomLocalEmployeeCreationInputs.inspect(transaction, principal, original,
        onRefused = { it }, onChecked = { it.reviewData() })
