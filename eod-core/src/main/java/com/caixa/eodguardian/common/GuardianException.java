package com.caixa.eodguardian.common;

/** Business exception carrying an HTTP-friendly status and a stable error code. */
public class GuardianException extends RuntimeException {
    private final int status;
    private final String code;

    public GuardianException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static GuardianException badRequest(String code, String msg) { return new GuardianException(400, code, msg); }
    public static GuardianException forbidden(String msg) { return new GuardianException(403, "FORBIDDEN", msg); }
    public static GuardianException unauthorized(String msg) { return new GuardianException(401, "UNAUTHORIZED", msg); }
    public static GuardianException notFound(String msg) { return new GuardianException(404, "NOT_FOUND", msg); }
    public static GuardianException conflict(String code, String msg) { return new GuardianException(409, code, msg); }
}
