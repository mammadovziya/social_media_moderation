package com.example.moderation.gateway;

import java.util.UUID;

final class RequestIdentifiers {
    static final String SAFE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:~-]{0,127}";

    private RequestIdentifiers() {}

    static String resolve(String supplied) {
        return supplied != null && supplied.matches(SAFE_PATTERN)
                ? supplied
                : UUID.randomUUID().toString();
    }
}
