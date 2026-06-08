package com.app.common.models;

import com.app.common.definitions.enums.Language;
import com.app.common.definitions.enums.Theme;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of a user's persisted settings, backed by a key-value map.
 *
 * Typed accessors cover well-known settings. Generic {@link #get} and
 * {@link #getOrDefault} allow reading any arbitrary key without touching this
 * class. Adding a new typed setting only requires adding one accessor method —
 * no structural change to the model, repository, or service.
 */
public class UserSetting {

    // Key constants are the single source of truth for each setting name.
    public static final String KEY_THEME = "theme";
    public static final String KEY_LANGUAGE = "language";

    private final Long userId;
    private final Map<String, String> values;

    public UserSetting(Long userId, Map<String, String> values) {
        this.userId = userId;
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public Long getUserId() {
        return userId;
    }

    // ── Typed accessors ──────────────────────────────────────────────────────

    /** Returns the user's stored theme, defaulting to LIGHT if not set or invalid. */
    public Theme getTheme() {
        return parseEnum(KEY_THEME, Theme.class, Theme.LIGHT);
    }

    /** Returns the user's stored language, defaulting to VI if not set or invalid. */
    public Language getLanguage() {
        return parseEnum(KEY_LANGUAGE, Language.class, Language.VI);
    }

    private <E extends Enum<E>> E parseEnum(String key, Class<E> enumType, E defaultValue) {
        return get(key)
                .map(value -> {
                    try {
                        return Enum.valueOf(enumType, value);
                    } catch (IllegalArgumentException ex) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }

    // ── Generic accessor ─────────────────────────────────────────────────────

    /** Returns the value for an arbitrary key, or empty if not present. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Returns the value for an arbitrary key, or {@code defaultValue} if not
     * present.
     */
    public String getOrDefault(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }
}
