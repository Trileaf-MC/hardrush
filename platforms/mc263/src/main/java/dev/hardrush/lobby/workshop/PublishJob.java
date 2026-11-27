package dev.hardrush.lobby.workshop;

import dev.hardrush.core.lobby.plan.PackManifest;
import dev.hardrush.core.lobby.plan.Problem;
import dev.hardrush.core.lobby.plan.Severity;
import dev.hardrush.lobby.pack.MarkerScan;
import dev.hardrush.lobby.pack.PackBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;

/**
 * Publishing a draft (design 11.5), spread over ticks: {@link PackBuild} loads, scans, checks and captures the draft's
 * build range; then the job waits for the publisher to confirm the authors and writes
 * {@code hardrush/lobby/packs/<id>-v<version>.hrlobby} on the IO thread.
 * Any failure, or the draft being deleted, ends the job and drops its chunk tickets.
 */
final class PublishJob {
    enum Stage { LOADING, SCANNING, CAPTURING, CONFIRM, WRITING, DONE, FAILED }
    static final long LOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long CONFIRM_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
    /** Problems listed at most, then "还有 N 条". */
    static final int SHOWN_PROBLEMS = 10;

    final String draftId;
    final UUID publisher;
    final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("发布"), BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS);
    private final PackBuild build;
    private long stageStarted = System.nanoTime();
    private Stage stage = Stage.LOADING;
    /** The job's own failures: timeouts and the write. The build's are read from it. */
    private final List<String> failures = new ArrayList<>();
    /** Participants the publisher struck from the authors. */
    final Set<UUID> excluded = new HashSet<>();
    private CompletableFuture<Path> writing;
    private Path written;

    PublishJob(Draft draft, UUID publisher, ServerLevel level, int maxSize) {
        this.draftId = draft.id();
        this.publisher = publisher;
        var range = draft.range(level.getMinY(), level.getMinY() + level.getHeight() - 1);
        var bottomY = draft.bottomY() == null ? OptionalInt.empty() : OptionalInt.of(draft.bottomY());
        this.build = new PackBuild(level, range, bottomY, maxSize);
    }

    Stage stage() { return stage; }
    List<String> warnings() {
        return build.problems().stream().filter(p -> p.severity() == Severity.WARNING).map(Problem::message).toList();
    }
    List<String> errors() {
        var errors = new ArrayList<String>();
        build.errors().stream().map(PublishJob::describe).forEach(errors::add);
        if (build.limit() != null) errors.add(build.limit());
        errors.addAll(failures);
        return List.copyOf(errors);
    }
    Path written() { return written; }
    MarkerScan.ScanResult scan() { return build.scan(); }

    /** Advances one step. */
    void tick() {
        switch (stage) {
            case LOADING, SCANNING, CAPTURING -> {
                if (build.stage() == PackBuild.Stage.LOADING && build.stageNanos() > LOAD_TIMEOUT_NANOS) {
                    fail("加载草稿区块超时（" + build.pendingChunks() + "）");
                    return;
                }
                build.tick();
                switch (build.stage()) {
                    case LOADING -> { }
                    case SCANNING -> stage = Stage.SCANNING;
                    case CAPTURING -> stage = Stage.CAPTURING;
                    case CAPTURED -> next(Stage.CONFIRM);
                    case FAILED -> fail(null);
                }
            }
            case CONFIRM -> { if (System.nanoTime() - stageStarted > CONFIRM_TIMEOUT_NANOS) fail("等待确认超过 10 分钟，发布已取消"); }
            case WRITING -> {
                if (!writing.isDone()) return;
                try {
                    written = writing.join();
                    next(Stage.DONE);
                } catch (RuntimeException e) {
                    var cause = e.getCause() == null ? e : e.getCause();
                    fail("写入大厅包失败：" + cause.getMessage());
                }
            }
            case DONE, FAILED -> { }
        }
    }

    /** Writes the pack with the participants not {@link #excluded} as authors. */
    void confirm(Draft draft, Path packs, Executor io) {
        if (stage != Stage.CONFIRM) throw new IllegalStateException("not waiting for confirmation");
        var authors = draft.participants().stream().filter(p -> !excluded.contains(p.id())).map(Draft.Participant::name)
                .limit(PackManifest.MAX_AUTHORS).toList();
        var name = draft.name().codePointCount(0, draft.name().length()) > PackManifest.MAX_NAME
                ? draft.name().substring(0, draft.name().offsetByCodePoints(0, PackManifest.MAX_NAME)) : draft.name();
        var captured = build.captured();
        int firstVersion = draft.publishedVersion() + 1;
        var created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        next(Stage.WRITING);
        writing = CompletableFuture.supplyAsync(() -> {
            try {
                // A pack of this id may be in the library from an earlier draft with the same id; never overwrite it.
                for (int version = firstVersion; ; version++) {
                    var manifest = captured.manifest(draft.id(), name, authors, version, created);
                    var file = packs.resolve(manifest.fileName());
                    if (Files.exists(file)) continue;
                    captured.write(file, manifest);
                    return file;
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, io);
    }

    void cancel() {
        build.close();
        if (stage != Stage.DONE) stage = Stage.FAILED;
    }

    /** For the bar and the menu. */
    String progress() {
        return switch (stage) {
            case LOADING, SCANNING, CAPTURING -> build.progress();
            case CONFIRM -> "等待确认作者";
            case WRITING -> "写入文件";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
    }

    float fraction() {
        return switch (stage) {
            case LOADING, SCANNING, CAPTURING -> build.fraction();
            case CONFIRM, WRITING -> .95f;
            case DONE, FAILED -> 1f;
        };
    }

    /** At most {@link #SHOWN_PROBLEMS} lines, then how many more. */
    static List<String> shown(List<String> lines) {
        if (lines.size() <= SHOWN_PROBLEMS) return lines;
        var out = new ArrayList<>(lines.subList(0, SHOWN_PROBLEMS));
        out.add("还有 " + (lines.size() - SHOWN_PROBLEMS) + " 条");
        return out;
    }

    private static String describe(Problem p) {
        return p.message() + (p.where() == null || p.message().contains(p.where().x() + " " + p.where().y() + " " + p.where().z())
                ? "" : "（" + p.where().x() + " " + p.where().y() + " " + p.where().z() + "）");
    }

    private void fail(String reason) {
        if (reason != null) failures.add(reason);
        build.close();
        next(Stage.FAILED);
    }

    private void next(Stage stage) {
        this.stage = stage;
        stageStarted = System.nanoTime();
    }
}
