package com.cribbagecounter;

import io.github.cdimascio.dotenv.Dotenv;

public class Env {
    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

    public static String get(String key) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return DOTENV.get(key);
    }

    public static int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

