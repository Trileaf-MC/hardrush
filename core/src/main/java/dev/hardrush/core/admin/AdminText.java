package dev.hardrush.core.admin;

import java.util.Objects;

/** Exact plain text for approved operations and audit records; unsafe input is never silently rewritten. */
public final class AdminText {
    public static final int MAX_REASON_CODE_POINTS = 512;
    private AdminText() { }

    public static String reason(String value) { return text(value, MAX_REASON_CODE_POINTS); }

    public static String text(String value, int maxCodePoints) {
        Objects.requireNonNull(value, "value");
        if (maxCodePoints < 1 || maxCodePoints > 4096 || value.isBlank()
                || value.length() > maxCodePoints * 2) {
            throw new IllegalArgumentException("Invalid administrator text bound");
        }
        int count = 0;
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            int type = Character.getType(point);
            if (++count > maxCodePoints || point == '§' || Character.isISOControl(point)
                    || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException("Unsafe or overlong administrator text");
            }
            offset += Character.charCount(point);
        }
        return value;
    }
}
