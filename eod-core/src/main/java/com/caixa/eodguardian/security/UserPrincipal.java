package com.caixa.eodguardian.security;

import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Authenticated user. Credentials are never part of this object. */
public record UserPrincipal(String username, String displayName, Set<Role> roles) implements JsonSerializable {

    public static final UserPrincipal SYSTEM = new UserPrincipal("system", "EOD Guardian (system)", Set.of());

    public boolean has(Permission p) {
        for (Role r : roles) if (r.permissions().contains(p)) return true;
        return false;
    }

    public Set<Permission> permissions() {
        Set<Permission> s = EnumSet.noneOf(Permission.class);
        for (Role r : roles) s.addAll(r.permissions());
        return s;
    }

    public String primaryRole() { return roles.isEmpty() ? "SYSTEM" : roles.iterator().next().name(); }

    @Override
    public Map<String, Object> toMap() {
        return Maps.of("username", username, "displayName", displayName, "roles", roles, "permissions", permissions());
    }
}
