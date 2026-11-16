package dev.hardrush.lobby.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.hardrush.lobby.pack.LobbyPlanJson;
import dev.hardrush.lobby.pack.LobbyStorage;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.RoundWorldPaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draft files under the world root, {@code hardrush/workshop/<id>/draft.json} (design 10.1), and the startup cleanup of
 * {@code dimensions/hardrush/workshop_*} (design 10.2). Writes go to a temporary file and move into place atomically.
 * IO only: call off the server thread.
 */
public final class WorkshopStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-workshop");
    private static final String FILE = "draft.json";

    private final Path worldRoot;
    private final Path root;

    public WorkshopStorage(Path worldRoot) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
        this.root = this.worldRoot.resolve("hardrush/workshop");
    }

    /** What the draft directories hold: readable drafts, and every directory name that looks like a draft. */
    public record Listing(Map<String, Draft> drafts, Set<String> directories) {}

    /**
     * Reads every draft. A directory whose {@code draft.json} cannot be read is reported but kept, and so is its
     * dimension: a damaged file must not cost the builders their work.
     */
    public Listing list() throws IOException {
        var drafts = new TreeMap<String, Draft>();
        var directories = new TreeSet<String>();
        RoundWorldPaths.checkAncestors(root, true);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return new Listing(drafts, directories);
        try (var children = Files.list(root)) {
            for (var child : children.toList()) {
                var id = child.getFileName().toString();
                // The import folder sits beside the drafts (design 19) and is never one.
                if (id.equals(ImportFolder.DIRECTORY) || !LobbyDimension.DRAFT_ID.matcher(id).matches()
                        || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                directories.add(id);
                try {
                    var draft = read(child.resolve(FILE));
                    if (!draft.id().equals(id)) throw new PackException("草稿 id 与目录名不一致：" + draft.id());
                    drafts.put(id, draft);
                } catch (IOException | PackException | RuntimeException error) {
                    LOGGER.warn("工坊草稿 {} 读取失败，保留目录与维度等待处理：{}", id, error.getMessage());
                }
            }
        }
        return new Listing(drafts, directories);
    }

    public void write(Draft draft) throws IOException {
        var json = LobbyPlanJson.write(toJson(draft)).getBytes(StandardCharsets.UTF_8);
        LobbyStorage.writeAtomically(root.resolve(draft.id()).resolve(FILE), new ByteArrayInputStream(json));
    }

    /** Deletes the draft's directory and its dimension's region files; the dimension must not be loaded. */
    public void delete(String id) throws IOException {
        LobbyStorage.deleteTree(dimension(id));
        LobbyStorage.deleteTree(root.resolve(id));
    }

    /** Deletes only the draft's directory: Fantasy removes a registered dimension's files itself. */
    public void deleteDraftDirectory(String id) throws IOException { LobbyStorage.deleteTree(root.resolve(id)); }

    /** {@code <world>/dimensions/hardrush/workshop_<id>}. */
    public Path dimension(String id) { return LobbyDimension.folder(worldRoot, LobbyDimension.workshopKey(id)); }

    /** Deletes every {@code workshop_<id>} dimension directory whose id is not in {@code keep} and is not loaded. */
    public void cleanup(Set<String> keep, Set<String> loaded) throws IOException {
        var dimensions = LobbyDimension.namespaceFolder(worldRoot);
        RoundWorldPaths.checkAncestors(dimensions, true);
        if (!Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(dimensions)) {
            for (var child : children.toList()) {
                var name = child.getFileName().toString();
                var id = LobbyDimension.workshopDraft(name);
                if (id.isEmpty() || keep.contains(id.get()) || loaded.contains(name)) continue;
                LOGGER.info("删除不属于任何草稿的工坊维度 {}", name);
                LobbyStorage.deleteTree(child);
            }
        }
    }

    private static Draft read(Path file) throws IOException, PackException {
        RoundWorldPaths.checkAncestors(file, false);
        return fromJson(LobbyPlanJson.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    static JsonObject toJson(Draft draft) {
        var json = new JsonObject();
        json.addProperty("id", draft.id());
        json.addProperty("name", draft.name());
        json.addProperty("source", draft.source());
        var participants = new JsonArray();
        for (var p : draft.participants()) {
            var o = new JsonObject();
            o.addProperty("uuid", p.id().toString());
            o.addProperty("name", p.name());
            participants.add(o);
        }
        json.add("participants", participants);
        json.addProperty("published_version", draft.publishedVersion());
        var center = new JsonArray();
        center.add(draft.borderCenterX());
        center.add(draft.borderCenterZ());
        json.add("border_center", center);
        json.addProperty("border_diameter", draft.borderDiameter());
        if (draft.bottomY() == null) json.add("bottom_y", JsonNull.INSTANCE); else json.addProperty("bottom_y", draft.bottomY());
        if (draft.entry() == null) json.add("entry", JsonNull.INSTANCE);
        else {
            var entry = new JsonArray();
            entry.add(draft.entry().x()); entry.add(draft.entry().y()); entry.add(draft.entry().z()); entry.add(draft.entry().yaw());
            json.add("entry", entry);
        }
        json.addProperty("state", draft.state().name());
        return json;
    }

    static Draft fromJson(JsonObject json) throws PackException {
        try {
            var participants = new ArrayList<Draft.Participant>();
            for (var element : json.getAsJsonArray("participants")) {
                var o = element.getAsJsonObject();
                participants.add(new Draft.Participant(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString()));
            }
            var center = json.getAsJsonArray("border_center");
            if (center.size() != 2) throw new IllegalArgumentException("border_center 需要两个数");
            JsonElement bottom = json.get("bottom_y");
            JsonElement entryJson = json.get("entry");
            dev.hardrush.core.lobby.plan.Point entry = null;
            if (entryJson != null && !entryJson.isJsonNull()) {
                var a = entryJson.getAsJsonArray();
                if (a.size() != 4) throw new IllegalArgumentException("entry 需要四个数");
                entry = new dev.hardrush.core.lobby.plan.Point(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsFloat());
                if (!Double.isFinite(entry.x() + entry.y() + entry.z())) throw new IllegalArgumentException("entry 不是有限数");
            }
            return new Draft(json.get("id").getAsString(), json.get("name").getAsString(), json.get("source").getAsString(),
                    participants, json.get("published_version").getAsInt(), center.get(0).getAsInt(), center.get(1).getAsInt(),
                    json.get("border_diameter").getAsInt(), bottom == null || bottom.isJsonNull() ? null : bottom.getAsInt(), entry,
                    Draft.State.valueOf(json.get("state").getAsString()));
        } catch (RuntimeException malformed) {
            throw new PackException("draft.json 格式错误：" + malformed.getMessage());
        }
    }
}
