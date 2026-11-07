package com.caixa.eodguardian.security;

import java.util.EnumSet;
import java.util.Set;

import static com.caixa.eodguardian.security.Permission.*;

/**
 * Roles with segregation of duties: investigation, patch making, patch checking and execution
 * are held by different roles so no single user can propose, approve and apply a data change.
 */
public enum Role {
    VIEWER(EnumSet.of(VIEW_DASHBOARD)),
    INVESTIGATOR(EnumSet.of(VIEW_DASHBOARD, INVESTIGATE)),
    MAKER(EnumSet.of(VIEW_DASHBOARD, INVESTIGATE, PATCH_SUBMIT)),
    CHECKER(EnumSet.of(VIEW_DASHBOARD, PATCH_APPROVE, VIEW_AUDIT)),
    EOD_OPERATOR(EnumSet.of(VIEW_DASHBOARD, EOD_CONTROL, PATCH_EXECUTE, PATCH_ROLLBACK, EOD_RESUME)),
    AUDITOR(EnumSet.of(VIEW_DASHBOARD, VIEW_AUDIT, VIEW_METRICS)),
    ADMIN(EnumSet.of(VIEW_DASHBOARD, EOD_CONTROL, SCENARIO_ADMIN, VIEW_AUDIT, VIEW_METRICS));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) { this.permissions = permissions; }

    public Set<Permission> permissions() { return permissions; }
}
