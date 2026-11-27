package dev.hardrush.lobby.workshop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.hardrush.HardRushProperties;
import dev.hardrush.core.lobby.plan.Box;
import dev.hardrush.core.lobby.plan.BuildManifest;
import dev.hardrush.core.lobby.plan.Cell;
import dev.hardrush.core.lobby.plan.PackManifest;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.gametest.TestProperties;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.lobby.pack.ChunkWarmup;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.lobby.pack.PackBuild;
import dev.hardrush.lobby.pack.PackException;
import dev.hardrush.lobby.pack.PackLimits;
import dev.hardrush.lobby.pack.PackTiles;
import dev.hardrush.lobby.pack.PreparedPack;
import dev.hardrush.lobby.pack.SanitizeMode;
import dev.hardrush.lobby.pack.imports.BuildManifestJson;
import dev.hardrush.world.LobbyDimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * The headless lobby pack build (“建筑清单与大厅包构建” 2): a source directory's building file and manifest are
 * imported into a throwaway void dimension exactly as a new structure draft would be, then packed by the same
 * {@link PackBuild} that publishing uses, over the range such a draft would have. Writes {@code out/report.json} and,
 * without errors, {@code out/<id>-v<version>.hrlobby}, read back as an install would. Runs only with
 * {@link TestProperties#LOBBY_PACK} ({@code scripts/lobby-pack.py build}); a pack with acceptance errors is a
 * finished build, so the test fails only when the tooling itself breaks (no dimension, chunks never load).
 */
public final class LobbyPackBuildGameTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SOURCE_MANIFEST = "lobby.json", OUT = "out", REPORT = "report.json";
    static final List<String> BUILDINGS = List.of("build.schem", "build.litematic", "build.nbt");

    /** GameTests tick unthrottled, so the limits that matter are the wall-clock ones below and the script's watchdog. */
    @GameTest(maxTicks = 2_000_000)
    public void buildsALobbyPackFromASourceDirectory(GameTestHelper test) {
        var property = System.getProperty(TestProperties.LOBBY_PACK);
        if (property == null || property.isBlank()) { test.succeed(); return; }
        var job = new Job(test.getLevel().getServer(), Path.of(property).toAbsolutePath().normalize());
        TestTeardown.atEnd(test, job::close);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(job.tick(), "Lobby pack build: " + job.progress()))
                .thenExecute(() -> LOGGER.info("大厅包构建完成：{}，报告 {}", job.ok ? "通过" : "有错误", job.out.resolve(REPORT)))
                .thenSucceed();
    }

    /** One build, ticked on the server thread. */
    static final class Job {
        enum Stage { HOLDING, IMPORTING, PACKING, WRITING, DONE }

        private final MinecraftServer server;
        private final Path source, out;
        private final int maxSize = HardRushProperties.lobbyMaxSize();
        private final long started = System.nanoTime();
        private Stage stage = Stage.HOLDING;
        private String id, name;
        private List<String> authors;
        private int version;
        private Path building;
        private RuntimeLevelHandle handle;
        /**
         * Every chunk of the range, loaded from before the import places anything until the pack is captured: a
         * temporary dimension never saves, so a chunk unloaded between the import and the build would come back empty.
         */
        private ChunkWarmup hold;
        private Box range;
        private DraftImport importing;
        private PackBuild build;
        private CompletableFuture<String> writing;
        private Cell structureMin, manifestOrigin;
        private Box bounds;
        private final List<String> notes = new ArrayList<>();
        private final List<JsonObject> problems = new ArrayList<>();
        private String pack;
        boolean ok;

        Job(MinecraftServer server, Path source) {
            this.server = server;
            this.source = source;
            this.out = source.resolve(OUT);
            try {
                readSource();
                open();
                importing = new DraftImport(null, DraftImport.structure(building, maxSize, DraftImport::centredOnOrigin), handle::asLevel);
            } catch (SourceException e) {
                problems.add(problem(Severity.ERROR, "SOURCE", null, e.getMessage(), null));
                finish();
            }
        }

        /** True once the report is written. Throws only when the tooling breaks. */
        boolean tick() {
            switch (stage) {
                case HOLDING -> {
                    if (System.nanoTime() - started > PublishJob.LOAD_TIMEOUT_NANOS) throw new IllegalStateException("加载区块超时（" + hold.pending() + "）");
                    if (hold.tick()) stage = Stage.IMPORTING;
                }
                case IMPORTING -> {
                    boolean done;
                    try { done = importing.tick(); }
                    catch (RuntimeException e) {
                        problems.add(problem(Severity.ERROR, "IMPORT", null, e.getMessage() == null ? e.toString() : e.getMessage(), null));
                        finish();
                        return true;
                    }
                    if (!done) return false;
                    var content = importing.content();
                    notes.addAll(content.notes());
                    structureMin = content.bounds().min();
                    try { manifestOrigin = BuildManifestJson.readBesides(building).map(BuildManifest::origin).orElse(new Cell(0, 0, 0)); }
                    catch (PackException e) { throw new IllegalStateException("建筑清单导入后读不回来", e); }
                    build = new PackBuild(handle.asLevel(), range, OptionalInt.empty(), maxSize);
                    stage = Stage.PACKING;
                }
                case PACKING -> {
                    if (build.stage() == PackBuild.Stage.LOADING && build.stageNanos() > PublishJob.LOAD_TIMEOUT_NANOS)
                        throw new IllegalStateException("加载区块超时（" + build.pendingChunks() + "）");
                    build.tick();
                    switch (build.stage()) {
                        case CAPTURED -> {
                            hold.close();
                            write(build.captured());
                        }
                        case FAILED -> {
                            hold.close();
                            report(build);
                            if (build.limit() != null) problems.add(problem(Severity.ERROR, "LIMIT", null, build.limit(), null));
                            finish();
                            return true;
                        }
                        default -> { }
                    }
                }
                case WRITING -> {
                    if (!writing.isDone()) return false;
                    try { pack = writing.join(); }
                    catch (RuntimeException e) {
                        var cause = e.getCause() == null ? e : e.getCause();
                        problems.add(problem(Severity.ERROR, "PACK", null, cause.getMessage() == null ? cause.toString() : cause.getMessage(), null));
                    }
                    finish();
                    return true;
                }
                case DONE -> { return true; }
            }
            return false;
        }

        String progress() {
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            return switch (stage) {
                case HOLDING -> "加载区块 · " + hold.pending() + "（" + seconds + " 秒）";
                case IMPORTING -> "导入 " + importing.progress() + "（" + seconds + " 秒）";
                case PACKING -> build.progress() + "（" + seconds + " 秒）";
                case WRITING -> "写入大厅包（" + seconds + " 秒）";
                case DONE -> "完成";
            };
        }

        void close() {
            if (importing != null) importing.cancel();
            if (build != null) build.close();
            if (hold != null) hold.close();
            if (handle != null) handle.delete();
        }

        private void open() {
            try {
                handle = LobbyDimension.openTemporary(server);
            } catch (RuntimeException e) {
                throw new IllegalStateException("临时维度打不开", e);
            }
            handle.setTickWhenEmpty(true);
            var level = handle.asLevel();
            // The range a new structure draft gets (centred on the origin, the server's size, every height), so the build
            // accepts exactly what publishing that draft would.
            range = Draft.create(id, name, "构建", maxSize).range(level.getMinY(), level.getMinY() + level.getHeight() - 1);
            hold = new ChunkWarmup(level, range);
        }

        /** {@code lobby.json} and the one building file (2.1); the header is checked as the pack's own would be. */
        private void readSource() throws SourceException {
            id = source.getFileName() == null ? "" : source.getFileName().toString();
            if (!PackManifest.ID.matcher(id).matches())
                throw new SourceException("源目录名 " + id + " 不是合法的包 id：只能用 1–32 个小写字母、数字、下划线或连字符");
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new SourceException("源目录不存在或不是目录：" + source);
            var file = source.resolve(SOURCE_MANIFEST);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new SourceException("缺少 " + SOURCE_MANIFEST);
            JsonObject json;
            try {
                var text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                var root = JsonParser.parseString(text);
                if (!root.isJsonObject()) throw new SourceException(SOURCE_MANIFEST + " 应当是一个 JSON 对象");
                json = root.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                throw new SourceException("读不了 " + SOURCE_MANIFEST + "：" + e.getMessage());
            }
            name = string(json.get("name"), "name");
            authors = new ArrayList<>();
            var list = json.get("authors");
            if (list != null && !list.isJsonNull()) {
                if (!list.isJsonArray()) throw new SourceException(SOURCE_MANIFEST + " 的 authors 应当是字符串数组");
                for (var author : list.getAsJsonArray()) authors.add(string(author, "authors"));
            }
            var number = json.get("version");
            if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber())
                throw new SourceException(SOURCE_MANIFEST + " 的 version 应当是整数");
            try { version = number.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException e) { throw new SourceException(SOURCE_MANIFEST + " 的 version 应当是整数"); }
            // The real bounds come from the scan, which checks them itself; a one-block stand-in leaves only the header's own problems.
            var header = new PackManifest(PackManifest.FORMAT, id, name, authors, version, "", "", PackTiles.currentDataVersion(),
                    new Box(new Cell(0, 0, 0), new Cell(0, 0, 0)));
            var wrong = header.problems(maxSize, PackTiles.currentDataVersion());
            if (!wrong.isEmpty()) throw new SourceException(SOURCE_MANIFEST + "：" + String.join("；", wrong));
            var found = BUILDINGS.stream().map(source::resolve).filter(p -> Files.exists(p, LinkOption.NOFOLLOW_LINKS)).toList();
            if (found.size() != 1)
                throw new SourceException(found.isEmpty() ? "缺少建筑文件（" + String.join("、", BUILDINGS) + " 之一）" : "建筑文件只能有一个，现在有 "
                        + String.join("、", found.stream().map(p -> p.getFileName().toString()).toList()));
            building = found.getFirst();
            if (!Files.isRegularFile(building, LinkOption.NOFOLLOW_LINKS))
                throw new SourceException(building.getFileName() + " 不是普通文件（不能是目录或符号链接）");
        }

        private static String string(JsonElement element, String key) throws SourceException {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                throw new SourceException(SOURCE_MANIFEST + " 的 " + key + " 应当是字符串");
            return element.getAsString();
        }

        /** Every acceptance problem of the build: the scan's errors and warnings, then what installing will strip. */
        private void report(PackBuild build) {
            if (build.scan() != null) bounds = build.scan().contentBounds();
            for (var p : build.problems()) problems.add(problem(p.severity(), p.kind().name(), p.function() == null ? null : p.function().id(), p.message(), p.where()));
        }

        /** Writes and reads back the pack on another thread (2.3 steps 3–4); a pack that does not read back is removed. */
        private void write(PackBuild.Captured captured) {
            report(build);
            var created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            var manifest = captured.manifest(id, name, authors, version, created);
            var target = out.resolve(manifest.fileName());
            stage = Stage.WRITING;
            writing = CompletableFuture.supplyAsync(() -> {
                try {
                    captured.write(target, manifest);
                    try {
                        PreparedPack.read(LobbyInstall.Source.file(target), PackLimits.forServer(maxSize), SanitizeMode.INSTALL);
                    } catch (IOException | PackException e) {
                        Files.deleteIfExists(target);
                        throw new IllegalStateException("写出的大厅包读不回来：" + e.getMessage(), e);
                    }
                    return OUT + "/" + target.getFileName();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException("写入大厅包失败：" + e.getMessage(), e);
                }
            });
        }

        private JsonObject problem(Severity severity, String kind, String function, String message, Cell where) {
            var o = new JsonObject();
            o.addProperty("severity", severity == Severity.ERROR ? "error" : "warning");
            o.addProperty("kind", kind);
            if (function != null) o.addProperty("function", function);
            o.addProperty("message", message);
            if (where != null) {
                o.add("world", cell(where));
                if (structureMin != null)
                    o.add("manifest", cell(new Cell(where.x() - structureMin.x() + manifestOrigin.x(), where.y() - structureMin.y() + manifestOrigin.y(),
                            where.z() - structureMin.z() + manifestOrigin.z())));
            }
            return o;
        }

        /** Writes the report (2.4) atomically; a pack without errors was written and read back, or it is an error itself. */
        private void finish() {
            stage = Stage.DONE;
            ok = pack != null && problems.stream().noneMatch(p -> p.get("severity").getAsString().equals("error"));
            var json = new JsonObject();
            json.addProperty("ok", ok);
            if (pack == null) json.add("pack", JsonNull.INSTANCE); else json.addProperty("pack", pack);
            if (structureMin == null) json.add("placement", JsonNull.INSTANCE);
            else {
                var placement = new JsonObject();
                placement.add("structure_min", cell(structureMin));
                placement.add("manifest_origin", cell(manifestOrigin));
                json.add("placement", placement);
            }
            if (bounds == null) json.add("bounds", JsonNull.INSTANCE);
            else {
                var box = new JsonObject();
                box.add("min", cell(bounds.min()));
                box.add("max", cell(bounds.max()));
                json.add("bounds", box);
            }
            var noteArray = new JsonArray();
            notes.forEach(noteArray::add);
            json.add("notes", noteArray);
            var problemArray = new JsonArray();
            problems.forEach(problemArray::add);
            json.add("problems", problemArray);
            try {
                Files.createDirectories(out);
                var temporary = Files.createTempFile(out, REPORT, ".tmp");
                Files.writeString(temporary, new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n", StandardCharsets.UTF_8);
                Files.move(temporary, out.resolve(REPORT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("写不了构建报告 " + out.resolve(REPORT), e);
            }
        }

        private static JsonArray cell(Cell c) {
            var a = new JsonArray();
            a.add(c.x()); a.add(c.y()); a.add(c.z());
            return a;
        }
    }

    /** The source directory itself is wrong (2.1); reported like an import failure. */
    private static final class SourceException extends Exception {
        SourceException(String message) { super(message); }
    }
}
