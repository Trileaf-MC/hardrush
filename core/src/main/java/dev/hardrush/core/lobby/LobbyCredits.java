package dev.hardrush.core.lobby;

import java.util.ArrayList;
import java.util.List;

/**
 * What the {@code credits} text slots say (design 3): the installed pack's name, version and builders. A sign has
 * four lines of about 90 pixels; whatever does not fit is cut, and a builder list that is cut ends with “等”. A text
 * display has room for everything.
 */
public final class LobbyCredits {
    /** {@code SignBlockEntity.getMaxTextLineWidth()} in vanilla. */
    public static final int SIGN_LINE_WIDTH = 90;
    private static final String BUILDERS = "建设者：", SEPARATOR = "、", MORE = "等", CUT = "…";

    private LobbyCredits() {}

    /** Four sign lines: the name, the version and up to two lines of builders. */
    public static List<String> signLines(String name, int version, List<String> authors) {
        var lines = new ArrayList<String>();
        lines.add(fit("大厅：" + name, CUT));
        lines.add("v" + version);
        var builders = new String[]{"", ""};
        if (!authors.isEmpty()) {
            builders[0] = BUILDERS;
            int line = 0;
            boolean cut = false;
            for (var author : authors) {
                var first = builders[line].isEmpty() || builders[line].equals(BUILDERS);
                var piece = first ? author : SEPARATOR + author;
                if (width(builders[line] + piece) <= SIGN_LINE_WIDTH) { builders[line] += piece; continue; }
                if (line == 0 && !first) {
                    line = 1;
                    if (width(author) <= SIGN_LINE_WIDTH) { builders[1] = author; continue; }
                }
                cut = true;
                // Room for part of a name only when nothing else is on the line yet.
                if (builders[line].isEmpty() || builders[line].equals(BUILDERS)) builders[line] += author;
                break;
            }
            if (cut) builders[line] = fit(builders[line], MORE);
        }
        lines.add(builders[0]);
        lines.add(builders[1]);
        return List.copyOf(lines);
    }

    /** One text for a text display, a line break between the pack and its builders. */
    public static String display(String name, int version, List<String> authors) {
        var text = "大厅：" + name + " v" + version;
        return authors.isEmpty() ? text : text + "\n" + BUILDERS + String.join(SEPARATOR, authors);
    }

    /**
     * {@code text} if it fits a sign line; otherwise as much as fits followed by {@code ending}. With {@link #MORE}
     * the ending is always added, since the caller cut a list.
     */
    static String fit(String text, String ending) {
        boolean always = ending.equals(MORE);
        if (!always && width(text) <= SIGN_LINE_WIDTH) return text;
        int budget = SIGN_LINE_WIDTH - width(ending);
        var out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int w = width(cp);
            if (used + w > budget) break;
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        var kept = out.toString();
        // A cut list should not end on its own separator.
        if (always && kept.endsWith(SEPARATOR)) kept = kept.substring(0, kept.length() - SEPARATOR.length());
        return kept + ending;
    }

    /** Estimated pixels in the default font: wide (CJK, full-width) glyphs 9, everything else 6. */
    static int width(String text) {
        return text.codePoints().map(LobbyCredits::width).sum();
    }
    private static int width(int cp) {
        boolean wide = cp >= 0x1100 && (cp <= 0x115F || cp >= 0x2E80 && cp <= 0xA4CF || cp >= 0xAC00 && cp <= 0xD7A3
                || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0xFE30 && cp <= 0xFE4F || cp >= 0xFF00 && cp <= 0xFF60
                || cp >= 0xFFE0 && cp <= 0xFFE6 || cp >= 0x20000);
        return wide ? 9 : 6;
    }
}
