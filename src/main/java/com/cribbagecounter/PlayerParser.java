package com.cribbagecounter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PlayerParser {
    private PlayerParser() {
    }

    public static List<String> parseUsernames(String csv) {
        Set<String> unique = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return new ArrayList<>();
        }

        String[] raw = csv.split(",");
        for (String value : raw) {
            String username = value.trim().toLowerCase(Locale.ROOT);
            if (!username.isBlank()) {
                unique.add(username);
            }
        }

        return new ArrayList<>(unique);
    }
}

