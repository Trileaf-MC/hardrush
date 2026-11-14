package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;

final class PackFixtures {
    static final Box BOUNDS = new Box(new Cell(-24, 63, -16), new Cell(39, 70, 31));
    static final int DATA_VERSION = 4000;
    static final PackLimits LIMITS = new PackLimits(256, DATA_VERSION);

    private PackFixtures() {}

    static PackManifest manifest() { return manifest(PackManifest.FORMAT, DATA_VERSION, BOUNDS); }
    static PackManifest manifest(int format, int dataVersion, Box bounds) {
        return new PackManifest(format, "default", "默认营地", List.of("LeifWebber", "粉丝"), 3, "2026-11-13T08:00:00Z",
                "1.0.0", dataVersion, bounds);
    }

    /** Every zone shape, block and entity triggers, models, points, lights and both text slot kinds. */
    static LobbyPlan plan() { return plan(BOUNDS); }
    static LobbyPlan plan(Box bounds) {
        var triggers = List.of(
                new Trigger(LobbyFunction.RULES, new Cell(1, 64, 2), "minecraft:lectern", new Point(1.5, 65, 2.5, 0)),
                new Trigger(LobbyFunction.HISTORY, null, null, new Point(-3.25, 64, 7.75, -90.5f)),
                new Trigger(LobbyFunction.VAULT, new Cell(5, 64, 5), "minecraft:vault", new Point(5.5, 65, 5.5, 180)));
        var models = List.of(new Model(LobbyFunction.ROLE_ZOMBIE, new Point(10.5, 64, -3.5, 45)));
        var points = new EnumMap<LobbyFunction, List<Point>>(LobbyFunction.class);
        points.put(LobbyFunction.LANDING_CENTRAL, List.of(new Point(0.5, 64, 0.5, 0), new Point(2.5, 64, 0.5, 90)));
        points.put(LobbyFunction.DISPLAY_SLOT, List.of(new Point(3, 66, 3, 0), new Point(1, 65, 3, 0)));
        var zones = new EnumMap<LobbyFunction, List<Zone>>(LobbyFunction.class);
        zones.put(LobbyFunction.ASSEMBLY, List.of(new Zone.Floor(Set.of(new Cell(0, 63, 0), new Cell(1, 63, 0), new Cell(0, 63, -1))),
                Zone.box(new Cell(20, 64, 20), 2, 3, 1)));
        zones.put(LobbyFunction.COURTYARD, List.of(Zone.box(new Cell(30, 64, 10), 5, 4, 5)));
        var lights = List.of(new Cell(-5, 64, -5), new Cell(-6, 64, -5));
        var texts = List.of(new TextSlot(LobbyFunction.STATUS, new Cell(2, 65, 2), new Point(2.5, 65, 2.5, 0)),
                new TextSlot(LobbyFunction.CREDITS, null, new Point(-1, 66, -1, 270)));
        return new LobbyPlan(bounds, triggers, models, points, zones, lights, texts);
    }

    /** A minimal valid structure template with one stone block. */
    static CompoundTag tile() {
        var tile = new CompoundTag();
        tile.put("size", ints(48, 48, 48));
        var palette = new ListTag();
        palette.add(state("minecraft:stone"));
        tile.put("palette", palette);
        var blocks = new ListTag();
        blocks.add(block(1, 2, 3, 0, null));
        tile.put("blocks", blocks);
        tile.put("entities", new ListTag());
        tile.putInt("DataVersion", DATA_VERSION);
        return tile;
    }

    static ListTag ints(int... values) {
        var list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }
    static CompoundTag state(String name) {
        var s = new CompoundTag();
        s.putString("Name", name);
        return s;
    }
    static CompoundTag block(int x, int y, int z, int state, CompoundTag nbt) {
        var b = new CompoundTag();
        b.put("pos", ints(x, y, z));
        b.putInt("state", state);
        if (nbt != null) b.put("nbt", nbt);
        return b;
    }
    static ListTag strings(String... values) {
        var list = new ListTag();
        for (var v : values) list.add(StringTag.valueOf(v));
        return list;
    }

    static byte[] json(PackManifest manifest, LobbyPlan plan) {
        return LobbyPlanJson.write(LobbyPlanJson.packToJson(manifest, plan)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static byte[] gzip(CompoundTag tag) throws IOException {
        var out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }
    /** A zip with exactly these entries, in order. */
    static byte[] zip(List<Map.Entry<String, byte[]>> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var e : entries) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
