package com.caixa.eodguardian.security;

import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Maps;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication and session management. Passwords are stored as PBKDF2-HMAC-SHA256 hashes,
 * sessions are opaque random tokens with expiry, and repeated failures lock the account.
 *
 * <p>Demo users are created from configuration; in production replace {@link #register} with
 * the bank's IdP (LDAP/AD/OIDC) and map directory groups to {@link Role}s.</p>
 */
public class AuthService {
    private static final int ITERATIONS = 120_000;
    private static final int MAX_FAILURES = 5;

    private record StoredUser(UserPrincipal principal, byte[] salt, byte[] hash) {}
    private record Session(String token, UserPrincipal user, Instant expiresAt) {}

    private final Map<String, StoredUser> users = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration sessionTtl;

    public AuthService(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

    public synchronized void register(String username, String displayName, Set<Role> roles, char[] password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        users.put(username, new StoredUser(new UserPrincipal(username, displayName, EnumSet.copyOf(roles)), salt, hash(password, salt)));
        Arrays.fill(password, '\0');
    }

    /** Registers the standard demonstration users with a single configured password. */
    public void registerDemoUsers(String password) {
        register("ravi.investigator", "Ravi Kumar (Investigator)", Set.of(Role.INVESTIGATOR), password.toCharArray());
        register("priya.maker", "Priya Shah (Ops Maker)", Set.of(Role.MAKER), password.toCharArray());
        register("arjun.checker", "Arjun Rao (Ops Checker)", Set.of(Role.CHECKER), password.toCharArray());
        register("meera.operator", "Meera Iyer (EOD Operator)", Set.of(Role.EOD_OPERATOR), password.toCharArray());
        register("sanjay.auditor", "Sanjay Menon (Auditor)", Set.of(Role.AUDITOR), password.toCharArray());
        register("admin", "Platform Administrator", Set.of(Role.ADMIN), password.toCharArray());
    }

    public String login(String username, char[] password) {
        if (username == null || password == null) throw GuardianException.unauthorized("Username and password are required");
        if (failures.getOrDefault(username, 0) >= MAX_FAILURES)
            throw new GuardianException(423, "ACCOUNT_LOCKED", "Account locked after repeated failures - contact administrator");
        StoredUser u;
        synchronized (this) { u = users.get(username); }
        boolean ok = u != null && MessageDigest.isEqual(u.hash(), hash(password, u.salt()));
        Arrays.fill(password, '\0');
        if (!ok) {
            failures.merge(username, 1, Integer::sum);
            throw GuardianException.unauthorized("Invalid username or password");
        }
        failures.remove(username);
        byte[] tok = new byte[32];
        random.nextBytes(tok);
        String token = HexFormat.of().formatHex(tok);
        sessions.put(token, new Session(token, u.principal(), Instant.now().plus(sessionTtl)));
        return token;
    }

    public Optional<UserPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (s.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s.user());
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public static void require(UserPrincipal user, Permission p) {
        if (user == null) throw GuardianException.unauthorized("Authentication required");
        if (!user.has(p)) throw GuardianException.forbidden(user.username() + " lacks permission " + p);
    }

    public synchronized List<Map<String, Object>> directory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredUser u : users.values())
            out.add(Maps.of("username", u.principal().username(), "displayName", u.principal().displayName(), "roles", u.principal().roles()));
        return out;
    }

    private static byte[] hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
