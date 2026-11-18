package com.example.smartattendance.util;

/** What the class QR code contains: PROXYPROOF1|sessionId|token */
public final class QrPayload {
    private static final String PREFIX = "PROXYPROOF1";

    public final String sessionId;
    public final String token;

    private QrPayload(String sessionId, String token) {
        this.sessionId = sessionId;
        this.token = token;
    }

    public static String encode(String sessionId, String token) {
        return PREFIX + "|" + sessionId + "|" + token;
    }

    /** Returns null for anything that is not a well-formed ProxyProof code. */
    public static QrPayload parse(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\|", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) return null;
        String sessionId = parts[1];
        String token = parts[2];
        // The session id becomes a Firestore path segment, so reject anything that could escape it.
        if (!sessionId.matches("[A-Za-z0-9]{1,64}") || !token.matches("[A-Za-z0-9]{8,64}")) return null;
        return new QrPayload(sessionId, token);
    }
}
