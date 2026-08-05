package com.example.moderation.gateway.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Language {
    AZ("az"),
    EN("en"),
    RU("ru"),
    TR("tr"),
    MIXED("mixed"),
    OTHER("other"),
    UND("und");

    private final String wireValue;

    Language(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static Language parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("language is required");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        for (Language language : values()) {
            if (language.wireValue.equals(normalized)) {
                return language;
            }
        }
        throw new IllegalArgumentException("unsupported language: " + value);
    }
}
