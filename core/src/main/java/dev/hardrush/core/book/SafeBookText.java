package dev.hardrush.core.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Plain text only. Never parse player input as JSON, selectors, legacy styles, or commands. */
public final class SafeBookText {
    public static final int MAX_PAGES = 80;
    public static final int MAX_PAGE_CHARACTERS = 768;
    private static final int LINE_UNITS = 24;
    private static final int PAGE_LINES = 12;
    private static final int MAX_INPUT_CHARACTERS = 65_536;

    private SafeBookText() { }

    public static String literal(String input, int maxCodePoints) {
        Objects.requireNonNull(input, "input");
        if (maxCodePoints < 1 || maxCodePoints > 4_096) throw new IllegalArgumentException("Invalid text bound");
        var out = new StringBuilder();
        int accepted = 0;
        int index = 0;
        // Only inspect a bounded prefix even if a caller supplies an untrusted enormous string.
        for (; index < input.length() && accepted < maxCodePoints; ) {
            int point = input.codePointAt(index);
            index += Character.charCount(point);
            int type = Character.getType(point);
            if (point == '§' || Character.isISOControl(point) || type == Character.FORMAT
                    || type == Character.SURROGATE || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else {
                out.appendCodePoint(point);
            }
            accepted++;
        }
        if (index < input.length()) out.append('…');
        String value = out.toString().strip();
        return value.isEmpty() ? "未知" : value;
    }

    /** Conservative CJK-aware wrapping, with finite pages and no split surrogate pairs. */
    public static List<String> paginate(List<String> paragraphs) {
        Objects.requireNonNull(paragraphs, "paragraphs");
        var text = new StringBuilder();
        for (String paragraph : paragraphs) {
            Objects.requireNonNull(paragraph, "paragraph");
            if (paragraph.length() > MAX_INPUT_CHARACTERS - text.length()) {
                throw new IllegalArgumentException("Book content exceeds bounded volume");
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(paragraph);
        }
        if (text.isEmpty()) throw new IllegalArgumentException("Empty book content");
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        int units = 0;
        for (int index = 0; index < text.length(); ) {
            int point = text.codePointAt(index);
            index += Character.charCount(point);
            if (point == '\n') {
                lines.add(line.toString()); line.setLength(0); units = 0;
                continue;
            }
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT
                    || Character.getType(point) == Character.SURROGATE || point == '§') {
                throw new IllegalArgumentException("Unsafe book text");
            }
            int width = width(point);
            if (!line.isEmpty() && units + width > LINE_UNITS) {
                lines.add(line.toString()); line.setLength(0); units = 0;
            }
            line.appendCodePoint(point); units += width;
            // Combining marks count towards the serialized payload even if their visual width is zero.
            if (line.length() >= LINE_UNITS * 2) {
                lines.add(line.toString()); line.setLength(0); units = 0;
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > MAX_PAGES * PAGE_LINES) throw new IllegalArgumentException("Too many book pages");
        var pages = new ArrayList<String>();
        for (int offset = 0; offset < lines.size(); offset += PAGE_LINES) {
            String page = String.join("\n", lines.subList(offset, Math.min(lines.size(), offset + PAGE_LINES)));
            if (page.length() > MAX_PAGE_CHARACTERS) throw new IllegalArgumentException("Book page exceeds bound");
            pages.add(page);
        }
        return List.copyOf(pages);
    }

    private static int width(int point) {
        int type = Character.getType(point);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) return 0;
        return point >= 0x1100 ? 2 : 1;
    }
}
