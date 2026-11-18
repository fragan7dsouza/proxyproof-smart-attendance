package com.example.smartattendance.util;

import java.security.SecureRandom;

public final class Codes {
    private static final SecureRandom RNG = new SecureRandom();
    /** No 0/O, 1/I/L: join codes get read aloud and copied off a board. */
    private static final String JOIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private Codes() {}

    public static String joinCode() {
        return random(JOIN_ALPHABET, 6);
    }

    /** 16 chars from 62 symbols, about 95 bits: unguessable within its 15-second life. */
    public static String token() {
        return random(TOKEN_ALPHABET, 16);
    }

    private static String random(String alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        return sb.toString();
    }
}
