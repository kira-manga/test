package com.ukkera.pos.app.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Explicit selection DATA from a protected employee detail, not current authority.
 * No grant, credential, bearer or native owner crosses the employee/setup roots.
 * The original native owner independently checks the branch and predecessor;
 * named-head approval and activation remain separate explicit decisions.
 */
public data class LocalSetupEmployeePolicyTarget(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val expectedPolicyRevision: Revision,
) {
    init { require(expectedPolicyRevision.value < Long.MAX_VALUE) }
    override fun toString(): String = "LocalSetupEmployeePolicyTarget([REDACTED])"
}
