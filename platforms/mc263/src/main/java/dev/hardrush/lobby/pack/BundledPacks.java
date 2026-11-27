package dev.hardrush.lobby.pack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lobby packs shipped in the mod jar (docs/plan/自带大厅包.md). On startup each is copied into the pack library
 * unless a file of that name is already there, and from then on it is an ordinary library pack: the menu, installing,
 * rolling back and starting a draft from it need nothing of their own. A new mod version brings a pack's new version
 * under a new file name, so nothing an owner has is overwritten.
 */
public final class BundledPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-lobby");
    static final String ROOT = "/hardrush/lobby/";
    static final String INDEX = ROOT + "bundled.txt";
    private static final Pattern RESOURCE = Pattern.compile("(bundled/)?[a-z0-9_-]{1,32}(-v[0-9]{1,9})?\\.hrlobby");
    private static final Pattern FILE = Pattern.compile("[a-z0-9_-]{1,32}-v[0-9]{1,9}\\.hrlobby");

    /** One shipped pack: where it is in the jar and what it is called in the library. */
    public record Entry(String resource, String fileName) {}

    private BundledPacks() {}

    /** The index, in menu-independent file order; a line it cannot read fails loudly, since the jar is ours. */
    public static List<Entry> index() throws IOException {
        var in = BundledPacks.class.getResourceAsStream(INDEX);
        if (in == null) throw new IOException("模组里缺少 " + INDEX);
        var out = new ArrayList<Entry>();
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var parts = line.split("\\s+");
                if (parts.length != 2 || !RESOURCE.matcher(parts[0]).matches() || !FILE.matcher(parts[1]).matches())
                    throw new IOException(INDEX + " 里有一行读不懂：" + line);
                out.add(new Entry(parts[0], parts[1]));
            }
        }
        return out;
    }

    /**
     * Copies every shipped pack that the library does not have yet; returns the names it added. IO only. A pack that
     * cannot be copied is logged and skipped: the lobby must still start.
     */
    public static List<String> release(Path library) {
        var added = new ArrayList<String>();
        List<Entry> entries;
        try { entries = index(); }
        catch (IOException e) { LOGGER.error("读取自带大厅包清单失败", e); return added; }
        for (var entry : entries) {
            var target = library.resolve(entry.fileName());
            try {
                // An owner's file of the same name wins, whatever it is.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var in = BundledPacks.class.getResourceAsStream(ROOT + entry.resource())) {
                    if (in == null) throw new IOException("模组里缺少 " + ROOT + entry.resource());
                    LobbyStorage.writeAtomically(target, in);
                }
                added.add(entry.fileName());
            } catch (IOException | RuntimeException e) {
                LOGGER.error("释放自带大厅包 {} 失败", entry.fileName(), e);
            }
        }
        if (!added.isEmpty()) LOGGER.info("已把模组自带的大厅包放进包库：{}", String.join("、", added));
        return added;
    }
}
