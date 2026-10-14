package dev.hardrush.persistence;

import java.util.Objects;

final class StoreValues {
    private StoreValues() {}

    static String text(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + maximumLength + " characters");
        }
        return value;
    }
}
