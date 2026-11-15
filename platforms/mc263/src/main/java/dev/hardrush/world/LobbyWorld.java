package dev.hardrush.world;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.lobby.plan.*;
import dev.hardrush.lobby.pack.ChunkWarmup;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.lobby.pack.LobbyInstallation;
import dev.hardrush.lobby.pack.LobbyStorage;
import dev.hardrush.player.PlayerProfiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * The installed lobby: which dimension and plan are live, whether they are ready, and every query by plan. Lobbies
 * come only from packs ({@link LobbyInstall}); on startup the recorded install is reopened as it is, or, on a fresh
 * world, the built-in pack is installed. Switching to a new install bumps {@link #planGeneration}, so everything
 * registered against the old plan is dropped and rebuilt.
 */
public final class LobbyWorld {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("hardrush-lobby");
    private static final Map<MinecraftServer, LobbyWorld> SERVICES = new IdentityHashMap<>();
    /** How far below the lobby's lowest block a player falls before being put back (design 10.5). */
    public static final int FALL_MARGIN = 8;

    private record Startup(Optional<LobbyInstallation> current, int highestSeq) {}

    private final MinecraftServer server;
    private final LobbyStorage storage;
    private final int maxSize = HardRushProperties.lobbyMaxSize();
    private final long started = System.nanoTime();
    private CompletableFuture<Startup> startup;
    /** Reopening the recorded install on startup: its handle and chunks until they are loaded. */
    private LobbyInstallation reopening;
    private RuntimeLevelHandle reopenHandle;
    private ChunkWarmup reopenChunks;
    private LobbyInstall install;

    private LobbyInstallation installation;
    private RuntimeLevelHandle handle;
    private ServerLevel level;
    /** Tickets that keep every chunk of the live lobby loaded. */
    private ChunkWarmup chunks;
    private BlockPos spawn;
    /** The runtime layout; every lobby position query reads it. */
    private LobbyPlan plan;
    private int generation, placedTiles;
    /** Per purpose, the candidate the next placement starts from, so several landings take turns. */
    private final Map<Landing, Integer> rotation = new EnumMap<>(Landing.class);
    private boolean ready;
    private String failure;

    private LobbyWorld(MinecraftServer server) {
        this.server = server;
        var root = server.getWorldPath(LevelResource.ROOT);
        storage = LobbyStorage.forWorld(root);
        // Directories of loaded dimensions are never deleted, whatever their name.
        var loaded = new HashSet<String>();
        for (var l : server.getAllLevels())
            if (l.dimension().identifier().getNamespace().equals(LobbyDimension.NAMESPACE)) loaded.add(l.dimension().identifier().getPath());
        startup = CompletableFuture.supplyAsync(() -> {
            try {
                var current = storage.readCurrent();
                // Never clean up around a record we could not read: it may name the lobby we would delete.
                int highest = storage.cleanup(root, current.map(c -> OptionalInt.of(c.seq())).orElse(OptionalInt.empty()), loaded);
                return new Startup(current, highest);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (dev.hardrush.lobby.pack.PackException e) {
                throw new CompletionException(e);
            }
        });
    }

    public static LobbyWorld open(MinecraftServer server) {
        return SERVICES.computeIfAbsent(server, LobbyWorld::new);
    }

    /** Only the live lobby dimension; a pack being installed is not the lobby yet. */
    public static boolean isLobby(ServerLevel level) {
        var service = SERVICES.get(level.getServer());
        return service != null && service.ready && service.level == level;
    }

    /** Bumped on every switch to another installed lobby; 0 before the first. Never opens the service. */
    public static int planGeneration(MinecraftServer server) {
        var service = SERVICES.get(server);
        return service == null ? 0 : service.generation;
    }
    public int planGeneration() { return generation; }

    public void tick() {
        if (failure != null) return;
        try {
            advance();
            if (ready) rescueFallen();
        } catch (RuntimeException error) {
            LOGGER.error("大厅准备失败", error);
            failure = "大厅准备失败：" + message(error);
        }
    }

    private void advance() {
        if (startup != null) {
            if (!startup.isDone()) { checkStartupTimeout(); return; }
            var result = startup.join();
            startup = null;
            if (result.current().isPresent()) reopen(result.current().get());
            else {
                int seq = result.highestSeq() + 1;
                install = new LobbyInstall(server, storage, LobbyInstall.Source.builtIn(), seq, LobbyDimension.key(seq), maxSize, OptionalInt.empty());
            }
        }
        if (reopening != null) {
            checkStartupTimeout();
            if (!reopenChunks.tick()) return;
            // A lobby whose landings cannot be stood on (say, region files lost) is not opened; its dimension is kept.
            for (var purpose : new Landing[]{Landing.CENTRAL, Landing.MONSTER})
                if (LandingRules.candidates(reopening.plan(), purpose).stream()
                        .noneMatch(p -> RoundWorldBundle.safeStanding(reopenHandle.asLevel(), BlockPos.containing(position(p)))))
                    throw new IllegalStateException("已安装的大厅落点不安全，保留维度等待修复：" + purpose);
            activate(reopening, reopenHandle, reopenChunks);
            reopening = null; reopenHandle = null; reopenChunks = null;
        }
        if (install != null) {
            var running = install;
            var outcome = running.tick();
            if (outcome.isEmpty()) return;
            install = null;
            placedTiles += running.placedTiles();
            switch (outcome.get()) {
                case LobbyInstall.Outcome.Installed done -> activate(done.installation(), done.handle(), done.chunks());
                case LobbyInstall.Outcome.Failed failed -> {
                    // TODO: [Refactor] M4 reports a failed install of a new pack to the operator who started it and keeps the
                    // live lobby; only the very first install leaves the server without a lobby.
                    if (!ready) throw new IllegalStateException(failed.reason());
                    LOGGER.warn("大厅包安装失败，保留当前大厅：{}", failed.reason());
                }
            }
        }
    }

    private void checkStartupTimeout() {
        if (System.nanoTime() - started > LobbyInstall.TIMEOUT_NANOS)
            throw new IllegalStateException("大厅加载超时" + (reopenChunks == null ? "" : "（" + reopenChunks.pending() + "）"));
    }

    /** Opens the recorded dimension as it is: nothing is placed again. */
    private void reopen(LobbyInstallation recorded) {
        var key = LobbyDimension.parse(recorded.dimension())
                .filter(k -> k.equals(LobbyDimension.key(recorded.seq())))
                .orElseThrow(() -> new IllegalStateException("current.json 记录的大厅维度不合法：" + recorded.dimension()));
        reopening = recorded;
        reopenHandle = LobbyDimension.open(server, key);
        reopenChunks = new ChunkWarmup(reopenHandle.asLevel(), recorded.plan().bounds());
    }

    /**
     * Switches every query to {@code next} in one tick (design 10.2 step 7): new plan generation, players in the old
     * lobby moved to the new central landing, old dimension deleted.
     */
    private void activate(LobbyInstallation next, RuntimeLevelHandle nextHandle, ChunkWarmup nextChunks) {
        var oldLevel = level;
        var oldHandle = handle;
        var oldChunks = chunks;
        installation = next;
        handle = nextHandle;
        level = nextHandle.asLevel();
        chunks = nextChunks;
        plan = next.plan();
        var central = LandingRules.candidates(plan, Landing.CENTRAL);
        if (central.isEmpty()) throw new IllegalStateException("大厅缺少中央落点");
        spawn = BlockPos.containing(position(central.getFirst()));
        LobbyDimension.spawn(level, spawn);
        rotation.clear();
        generation++;
        ready = true;
        LOGGER.info("大厅已就绪：{} v{}（{}，第 {} 代布局）", next.pack().name(), next.pack().version(), next.dimension(), generation);
        if (oldLevel == null || oldLevel == level) return;
        for (var player : List.copyOf(oldLevel.players())) relocate(player);
        oldChunks.close();
        oldHandle.delete();
    }

    /** To the central landing, through the profile service when it owns the body, so lobby mode is reapplied. */
    private void relocate(ServerPlayer player) {
        var profiles = PlayerProfiles.of(server).orElse(null);
        if (profiles != null && profiles.manages(player) && !player.isSpectator() && safeLanding(Landing.CENTRAL)) {
            profiles.placeInLobby(player, Landing.CENTRAL);
            return;
        }
        var spot = scatteredLanding(Landing.CENTRAL);
        var p = spot.position();
        player.teleportTo(level, p.x, p.y, p.z, Set.of(), spot.yaw(), 0, true);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    /**
     * A void lobby has no ground below its build, and lobby damage is cancelled, so a player who walks off falls for
     * ever (design 10.5). Spectators fly where they like: administrative spectators and observers are left alone.
     */
    private void rescueFallen() {
        int floor = plan.bounds().min().y() - FALL_MARGIN;
        for (var player : List.copyOf(level.players())) {
            if (player.getY() >= floor || player.isSpectator() || !player.isAlive()) continue;
            LOGGER.info("{} 掉出了大厅，送回中央落点", player.getScoreboardName());
            relocate(player);
        }
    }

    private static String message(Throwable error) {
        var cause = error;
        while ((cause instanceof CompletionException || cause instanceof UncheckedIOException) && cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void label(BlockPos pos, String[] lines) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        var messages = Arrays.stream(lines).map(Component::literal).map(c -> (Component) c).toList();
        var text = new SignText(messages, messages, DyeColor.BLACK, false);
        sign.setText(text, SignTextSlot.FRONT);
        sign.setText(text, SignTextSlot.BACK); sign.setWaxed(true); sign.setChanged();
    }
    /** Writes every status text slot; the stage and mode lines change with the round. */
    public void statusLabel(String stage, String mode) {
        if (!ready) return;
        for (var slot : plan.texts(LobbyFunction.STATUS))
            if (slot.sign() != null) label(pos(slot.sign()), new String[]{"HardRush 营地", stage, mode, "前方：集结区"});
    }
    public LobbyPlan plan() {
        if (!ready) throw new IllegalStateException("大厅尚未就绪");
        return plan;
    }
    /** The live lobby dimension. */
    public ResourceKey<Level> key() {
        if (!ready) throw new IllegalStateException("大厅尚未就绪");
        return level.dimension();
    }
    /** What {@code current.json} records about the live lobby. */
    public Optional<LobbyInstallation> installation() { return ready ? Optional.of(installation) : Optional.empty(); }
    /** Pack tiles placed by installs since this server started; zero when the recorded lobby was reopened. */
    public int placedTiles() { return placedTiles; }
    /** Plan coordinates are already absolute. */
    public static Vec3 position(Point point) { return new Vec3(point.x(), point.y(), point.z()); }
    public static BlockPos pos(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
    /** Whether at least one of the purpose's landings can be stood on. */
    public boolean safeLanding(Landing purpose) {
        if (level == null || plan == null) return false;
        return LandingRules.candidates(plan, purpose).stream().anyMatch(this::safe);
    }
    private boolean safe(Point point) { return RoundWorldBundle.safeStanding(level, BlockPos.containing(position(point))); }
    /** The safe landing the next placement for {@code purpose} uses; candidates take turns, skipping unsafe ones. */
    public Optional<Point> landing(Landing purpose) {
        var candidates = LandingRules.candidates(plan(), purpose);
        int start = rotation.getOrDefault(purpose, 0);
        for (int i = 0; i < candidates.size(); i++) {
            var point = candidates.get((start + i) % candidates.size());
            if (safe(point)) return Optional.of(point);
        }
        return Optional.empty();
    }
    /** Where a placement lands, and the facing that goes with it. */
    public record LandingSpot(Vec3 position, float yaw) {}
    /**
     * Vanilla's respawn scatter around a purpose landing, so arrivals do not stack: the lobby's own
     * {@code respawn_radius} on the landing's layer, limited to the purpose's zone and to open floor. Keeps
     * the exact landing otherwise. Advances the purpose's rotation, so the next arrival tries another landing.
     */
    public LandingSpot scatteredLanding(Landing purpose) {
        var candidates = LandingRules.candidates(plan(), purpose);
        if (candidates.isEmpty()) throw new IllegalStateException("大厅缺少落点：" + purpose);
        // Without a safe landing, keep the first one: callers check safeLanding before teleporting.
        var chosen = landing(purpose).orElse(candidates.getFirst());
        rotation.put(purpose, (candidates.indexOf(chosen) + 1) % candidates.size());
        var exact = BlockPos.containing(position(chosen));
        var feet = NativeScatter.first(exact, NativeScatter.radius(level, exact), column -> {
            var standing = new BlockPos(column.getX(), exact.getY(), column.getZ());
            return LandingRules.scatterAllowed(plan, purpose, chosen, standing.getX(), standing.getY(), standing.getZ())
                    && RoundWorldBundle.safeStanding(level, standing)
                    && level.getBlockState(standing).isAir() && level.getBlockState(standing.above()).isAir() ? standing : null;
        });
        return new LandingSpot(feet == null ? position(chosen) : Vec3.atBottomCenterOf(feet), chosen.yaw());
    }
    public boolean inAssembly(ServerPlayer player, boolean staying) {
        if (!ready || player.level() != level || !player.isAlive()) return false;
        return plan.inZone(LobbyFunction.ASSEMBLY, player.getX(), player.getY(), player.getZ(), staying);
    }
    /** Standing on an altar pad (jumping included). */
    public boolean onAltar(ServerPlayer player) { return altarUnder(player).isPresent(); }
    /** The altar pad the player stands on, for effects at its centre. */
    public Optional<Zone> altarUnder(ServerPlayer player) {
        if (!ready || player.level() != level || !player.isAlive()) return Optional.empty();
        return plan.zoneAt(LobbyFunction.ALTAR_PAD, player.getX(), player.getY(), player.getZ(), false);
    }
    /**
     * Light or darken every altar beam by swapping the owned glass above its beacon. Vanilla does the
     * rest: tinted glass stops a beam, and the beacon plays its own activate/deactivate sound.
     */
    public void altarLit(boolean lit) {
        if (!ready) return;
        var yellow = Blocks.STAINED_GLASS.pick(DyeColor.YELLOW);
        var want = lit ? yellow : Blocks.TINTED_GLASS;
        for (var cell : plan.altarLights()) {
            var pos = pos(cell);
            var actual = level.getBlockState(pos);
            // Only our two glass states are ours to swap.
            if (!actual.is(Blocks.TINTED_GLASS) && !actual.is(yellow)) continue;
            if (!actual.is(want)) dev.hardrush.protection.ProtectionRuntime.ownedWrite(level, () -> level.setBlockAndUpdate(pos, want.defaultBlockState()));
        }
    }

    public boolean ready() { return ready; }
    public Optional<String> failure() { return Optional.ofNullable(failure); }
    public static String status(MinecraftServer server) {
        var service = SERVICES.get(server);
        if (service == null) return "未启用";
        if (service.ready) return "已完成（" + service.installation.pack().name() + " v" + service.installation.pack().version() + "）";
        return service.failure().orElse("进行中");
    }
    /** Before readiness, only frozen profile quarantine may use the original overworld. */
    public ServerLevel holdingLevel() { return ready ? level : server.overworld(); }
    public Vec3 holdingSpawn() { return Vec3.atBottomCenterOf(ready ? spawn : server.overworld().getRespawnData().pos()); }

    public static void shutdown(MinecraftServer server) {
        var service = SERVICES.remove(server);
        if (service == null) return;
        // Tickets only: an unfinished install's dimension is left for the next startup's cleanup, and the live one
        // stays registered for vanilla's save, close and region-IO drain.
        if (service.chunks != null) service.chunks.close();
        if (service.reopenChunks != null) service.reopenChunks.close();
    }
}
