package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.StatusBars;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.LobbyWorld;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installing packs into the running server (design 10.2, 12): the pack library, one install at a time, rolling back to
 * the previous lobby, and the switch, which waits until the round allows it. The {@code /hardrush lobby} menu (level 4)
 * calls these; nothing here checks permissions. Outcomes reach the operators through {@link Notices#operators}, and a
 * status bar shows them the progress.
 */
public final class LobbyAdmin {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-lobby");
    /** How long a library listing is reused before the menu's next poll reads the directory again. */
    private static final long LIBRARY_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** Whether a request was taken, and what to tell the operator either way. */
    public record Result(boolean accepted, String message) {
        static Result accepted(String message) { return new Result(true, message); }
        static Result refused(String message) { return new Result(false, message); }
    }

    /** What the menu and the status bar show about the running install. */
    public record Progress(String pack, LobbyInstall.Stage stage, int placedTiles, int totalTiles, boolean waitingForSwitch,
                           long waitingSeconds, boolean cancellable, Optional<UUID> requester) {}

    /**
     * One {@code packs/*.hrlobby} as the library lists it. {@code problems} keep it from installing; {@code fallbacks}
     * are required functions it lacks, which installing covers with stand-ins; {@code warnings} are acceptance warnings.
     */
    public record PackSummary(Path file, String fileName, long size, Optional<PackManifest> manifest, List<String> problems,
                              List<LobbyFunction> fallbacks, List<String> warnings) {
        public PackSummary {
            problems = List.copyOf(problems); fallbacks = List.copyOf(fallbacks); warnings = List.copyOf(warnings);
        }
        public boolean installable() { return manifest.isPresent() && problems.isEmpty(); }
    }

    /** The latest listing; {@code loading} while a newer one is being read. Empty and loading at first. */
    public record Library(List<PackSummary> packs, boolean loading) {
        public Library { packs = List.copyOf(packs); }
    }

    private static final class Job {
        final LobbyInstall install;
        final UUID requester;
        final String label;
        final ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("大厅包"),
                BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
        boolean announcedReady;
        Job(LobbyInstall install, UUID requester, String label) { this.install = install; this.requester = requester; this.label = label; }
    }

    private record Cached(long size, long modified, PackSummary summary) {}
    private static final class LibraryCache {
        volatile List<PackSummary> packs = List.of();
        CompletableFuture<List<PackSummary>> refreshing;
        long refreshedAt;
        boolean refreshed;
        /** IO thread only. */
        final Map<Path, Cached> files = new HashMap<>();
    }

    private static final Map<MinecraftServer, Job> JOBS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, LibraryCache> LIBRARIES = new IdentityHashMap<>();

    private LobbyAdmin() {}

    /** Starts installing {@code pack}, a file in the pack library. Only one install runs at a time. */
    public static Result install(MinecraftServer server, Path pack, ServerPlayer requester) {
        requireThread(server);
        var lobby = LobbyWorld.open(server);
        var refusal = refusal(server, lobby);
        if (refusal != null) return Result.refused(refusal);
        var file = pack.toAbsolutePath().normalize();
        var library = lobby.storage().packs();
        if (!file.getParent().equals(library) || !file.getFileName().toString().endsWith(PackManifest.EXTENSION)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            return Result.refused("只能安装大厅包库（hardrush/lobby/packs）里的 .hrlobby 文件：" + file.getFileName());
        return start(server, lobby, LobbyInstall.Source.file(file), file.getFileName().toString(), requester);
    }

    /** Installs the copy of the lobby that was live before the current one again. */
    public static Result rollback(MinecraftServer server, ServerPlayer requester) {
        requireThread(server);
        var lobby = LobbyWorld.open(server);
        var refusal = refusal(server, lobby);
        if (refusal != null) return Result.refused(refusal);
        var found = previous(lobby);
        if (found.copy() == null) return Result.refused(found.problem());
        var copy = found.copy();
        var source = new LobbyInstall.Source() {
            public String name() { return "上一个大厅"; }
            public InputStream open() throws IOException { return LobbyInstall.Source.file(copy).open(); }
        };
        return start(server, lobby, source, "上一个大厅", requester);
    }

    /** Why a roll-back is not possible right now; empty when it is. For the menu's greyed button. */
    public static Optional<String> rollbackRefusal(MinecraftServer server) {
        requireThread(server);
        var lobby = LobbyWorld.open(server);
        var refusal = refusal(server, lobby);
        if (refusal != null) return Optional.of(refusal);
        var found = previous(lobby);
        return found.copy() == null ? Optional.of(found.problem()) : Optional.empty();
    }

    /** The previous lobby's installed copy, or why there is none. */
    private record Previous(Path copy, String problem) {}

    private static Previous previous(LobbyWorld lobby) {
        int current = lobby.installation().orElseThrow().seq();
        try {
            var copies = lobby.storage().installedCopies().headMap(current);
            return copies.isEmpty() ? new Previous(null, "没有上一个大厅可以回去") : new Previous(copies.get(copies.lastKey()), null);
        } catch (IOException e) {
            return new Previous(null, "读取已安装的大厅副本失败：" + e.getMessage());
        }
    }

    /** Abandons the running install and deletes its dimension; not once it is being recorded. */
    public static Result cancel(MinecraftServer server, ServerPlayer requester) {
        requireThread(server);
        var job = JOBS.get(server);
        if (job == null) return Result.refused("没有正在进行的大厅安装");
        if (!job.install.cancellable()) return Result.refused("大厅正在切换，已经不能取消");
        job.install.cancel();
        finish(server, job);
        var who = requester == null ? "" : requester.getScoreboardName() + " ";
        Notices.operators(server, who + "取消了大厅包 " + job.label + " 的安装");
        return Result.accepted("已取消安装 " + job.label);
    }

    public static Optional<Progress> progress(MinecraftServer server) {
        var job = JOBS.get(server);
        if (job == null) return Optional.empty();
        var install = job.install;
        return Optional.of(new Progress(name(job), install.stage(), install.placedTiles(), install.totalTiles(),
                install.stage() == LobbyInstall.Stage.READY, TimeUnit.NANOSECONDS.toSeconds(install.waitingNanos()),
                install.cancellable(), Optional.ofNullable(job.requester)));
    }

    /**
     * The pack library, read on an IO thread and cached; a poll after the cache has aged starts a new read and still
     * returns the previous listing at once.
     */
    public static Library library(MinecraftServer server) {
        requireThread(server);
        var lobby = LobbyWorld.open(server);
        var cache = LIBRARIES.computeIfAbsent(server, ignored -> new LibraryCache());
        if (cache.refreshing != null && cache.refreshing.isDone()) {
            try { cache.packs = cache.refreshing.join(); }
            catch (RuntimeException e) { LOGGER.warn("读取大厅包库失败", e); }
            cache.refreshing = null;
            cache.refreshed = true;
            cache.refreshedAt = System.nanoTime();
        }
        if (cache.refreshing == null && (!cache.refreshed || System.nanoTime() - cache.refreshedAt > LIBRARY_TTL_NANOS)) {
            var directory = lobby.storage().packs();
            var limits = PackLimits.forServer(lobby.maxSize());
            int maxSize = lobby.maxSize();
            cache.refreshing = CompletableFuture.supplyAsync(() -> scan(cache, directory, limits, maxSize));
        }
        return new Library(cache.packs, cache.refreshing != null);
    }

    /** Every server tick, after the lobby's own: drives the install and switches once the round allows it. */
    public static void tick(MinecraftServer server) {
        var job = JOBS.get(server);
        if (job == null) return;
        var outcome = job.install.tick(switchAllowed(server));
        if (outcome.isEmpty()) {
            if (job.install.stage() == LobbyInstall.Stage.READY && !job.announcedReady) {
                job.announcedReady = true;
                Notices.operators(server, "大厅包 " + name(job) + " 已准备好，等本轮回到准备阶段后切换");
            }
            bar(server, job);
            return;
        }
        finish(server, job);
        switch (outcome.get()) {
            case LobbyInstall.Outcome.Failed failed -> Notices.operators(server, "大厅包安装失败，当前大厅不变：" + failed.reason());
            case LobbyInstall.Outcome.Installed done -> {
                LobbyWorld.open(server).switchTo(done, player -> landing(server, player));
                var pack = done.installation().pack();
                Notices.operators(server, "已切换到大厅 " + pack.name() + " v" + pack.version());
                if (!done.fallbacks().functions().isEmpty())
                    Notices.operators(server, "大厅包缺少：" + String.join("、", done.fallbacks().functions().stream()
                            .map(LobbyFunction::displayName).toList()) + "，已在中央落点旁放置临时设施");
                if (!done.warnings().isEmpty())
                    Notices.operators(server, "大厅包验收警告：" + String.join("；", done.warnings().stream().map(Problem::message).toList()));
            }
        }
    }

    /** Server stop: tickets go, the dimension is left for the next startup (see {@link LobbyInstall#release}). */
    public static void shutdown(MinecraftServer server) {
        var job = JOBS.remove(server);
        if (job != null) { job.install.release(); job.bar.removeAllPlayers(); }
        LIBRARIES.remove(server);
    }

    /**
     * The switch condition (design 10.2): the session is open, the round is preparing or waiting, and no full
     * checkpoint is being saved.
     */
    static boolean switchAllowed(MinecraftServer server) {
        if (RoundRuntime.sessionState(server).filter(s -> s == RoundSession.State.OPEN).isEmpty()) return false;
        var phase = RoundRuntime.snapshot(server).map(r -> r.phase()).orElse(null);
        return (phase == RoundPhase.PREPARING || phase == RoundPhase.WAITING)
                && !dev.hardrush.storage.FullCheckpointRuntime.saving(server);
    }

    /** Ready players keep their place in the assembly zone of the new lobby, so the switch does not cancel them. */
    static Landing landing(MinecraftServer server, ServerPlayer player) {
        var participant = RoundRuntime.snapshot(server).map(r -> r.participants().get(player.getUUID())).orElse(null);
        return participant != null && participant.ready() && participant.role() == ParticipantRole.LOBBY ? Landing.RESTORE_READY : Landing.CENTRAL;
    }

    private static String refusal(MinecraftServer server, LobbyWorld lobby) {
        if (!lobby.ready() || lobby.installation().isEmpty()) return "大厅还没准备好，稍后再试";
        if (JOBS.containsKey(server)) return "已经有一个大厅包在安装：" + name(JOBS.get(server));
        return null;
    }

    private static Result start(MinecraftServer server, LobbyWorld lobby, LobbyInstall.Source source, String label, ServerPlayer requester) {
        int seq = lobby.nextSeq();
        LobbyInstall install;
        try {
            install = new LobbyInstall(server, lobby.storage(), source, seq, LobbyDimension.key(seq), lobby.maxSize(),
                    OptionalInt.of(lobby.installation().orElseThrow().seq()));
        } catch (RuntimeException e) {
            LOGGER.warn("无法开始安装大厅包 {}", label, e);
            return Result.refused("无法开始安装：" + e.getMessage());
        }
        var job = new Job(install, requester == null ? null : requester.getUUID(), label);
        JOBS.put(server, job);
        var who = requester == null ? "控制台" : requester.getScoreboardName();
        Notices.operators(server, who + " 开始安装大厅包 " + label + "；本轮回到准备阶段时切换，大厅里的人会被传到新大厅");
        bar(server, job);
        return Result.accepted("开始安装 " + label);
    }

    private static void finish(MinecraftServer server, Job job) {
        JOBS.remove(server, job);
        StatusBars.hideEverywhere(server, job.bar);
    }

    private static String name(Job job) {
        return job.install.manifest().map(m -> m.name() + " v" + m.version()).orElse(job.label);
    }

    /** Level-4 operators see what the install is doing; a waiting install says what it waits for. */
    private static void bar(MinecraftServer server, Job job) {
        var install = job.install;
        boolean waiting = install.stage() == LobbyInstall.Stage.READY;
        String text = waiting ? "大厅包 " + name(job) + " 已就绪 · 等待本轮回到准备阶段"
                : install.stage().compareTo(LobbyInstall.Stage.READY) > 0 ? "正在切换到大厅包 " + name(job)
                : "正在安装大厅包 " + name(job) + (install.totalTiles() > 0 ? " · " + install.placedTiles() + "/" + install.totalTiles() : "");
        job.bar.setName(Notices.bar(text));
        job.bar.setColor(waiting ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.BLUE);
        float progress = waiting ? 1f - Math.min(1f, (float) install.waitingNanos() / LobbyInstall.SWITCH_WAIT_NANOS)
                : install.totalTiles() == 0 ? 0f : (float) install.placedTiles() / install.totalTiles();
        job.bar.setProgress(Math.max(0, Math.min(1, progress)));
        for (var player : server.getPlayerList().getPlayers()) {
            if (net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_OWNERS).test(player.createCommandSourceStack()))
                StatusBars.show(player, StatusBars.Slot.LOBBY_INSTALL, job.bar);
            else StatusBars.hide(player, StatusBars.Slot.LOBBY_INSTALL);
        }
    }

    /** IO thread: one summary per pack file, reusing those whose size and time did not change. */
    private static List<PackSummary> scan(LibraryCache cache, Path directory, PackLimits limits, int maxSize) {
        var summaries = new ArrayList<PackSummary>();
        var seen = new HashSet<Path>();
        try {
            dev.hardrush.world.RoundWorldPaths.checkAncestors(directory, true);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { cache.files.clear(); return List.of(); }
            List<Path> files;
            try (var list = Files.list(directory)) {
                files = list.filter(f -> f.getFileName().toString().endsWith(PackManifest.EXTENSION)
                        && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
            }
            for (var file : files) {
                seen.add(file);
                long size = Files.size(file), modified = Files.getLastModifiedTime(file).toMillis();
                var cached = cache.files.get(file);
                if (cached != null && cached.size() == size && cached.modified() == modified) { summaries.add(cached.summary()); continue; }
                var summary = summarize(file, size, limits, maxSize);
                cache.files.put(file, new Cached(size, modified, summary));
                summaries.add(summary);
            }
        } catch (IOException e) {
            LOGGER.warn("读取大厅包库失败", e);
        }
        cache.files.keySet().retainAll(seen);
        return summaries;
    }

    static PackSummary summarize(Path file, long size, PackLimits limits, int maxSize) {
        var name = file.getFileName().toString();
        try {
            var header = PackFile.readHeader(file, limits);
            if (header.plan().isEmpty())
                return new PackSummary(file, name, size, Optional.of(header.manifest()), header.problems(), List.of(), List.of());
            var problems = LobbyPlanValidator.validate(header.plan().get(), maxSize);
            var fallbacks = LobbyPlanValidator.missingWithFallback(problems);
            var errors = problems.stream().filter(p -> p.severity() == Severity.ERROR)
                    .filter(p -> !(p.kind() == ProblemKind.MISSING && fallbacks.contains(p.function()))).map(Problem::message).toList();
            var warnings = problems.stream().filter(p -> p.severity() == Severity.WARNING).map(Problem::message).toList();
            return new PackSummary(file, name, size, Optional.of(header.manifest()), errors, fallbacks, warnings);
        } catch (PackException e) {
            return new PackSummary(file, name, size, Optional.empty(), List.of(e.getMessage()), List.of(), List.of());
        }
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Lobby administration runs on the server thread");
    }
}
