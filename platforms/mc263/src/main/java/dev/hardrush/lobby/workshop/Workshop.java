package dev.hardrush.lobby.workshop;

import dev.hardrush.HardRushProperties;
import dev.hardrush.lobby.ButtonMenu;
import dev.hardrush.lobby.pack.LobbyInstall;
import dev.hardrush.lobby.pack.LobbyStorage;
import dev.hardrush.notice.Notices;
import dev.hardrush.notice.StatusBars;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyDimension;
import dev.hardrush.world.LobbyWorld;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

/**
 * The workshop (design 11): drafts, their dimensions and the builders in them. A builder's body is handled by
 * {@link PlayerProfiles} (11.2); this service decides when builders must go back (11.3), keeps their dimensions open
 * while someone builds or an import runs, and owns the draft files. Server thread only; file IO on its own thread.
 */
public final class Workshop {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-workshop");
    private static final Map<MinecraftServer, Workshop> SERVICES = new IdentityHashMap<>();
    public static final int MAX_DRAFTS = 8;
    /** A dimension nobody builds in is saved at once and unloaded after this many ticks. */
    static final int IDLE_UNLOAD_TICKS = 100;
    /** How far below the dimension's bottom a builder falls before being put back at the entry. */
    public static final int VOID_MARGIN = 16;
    /** Where builders arrive in a draft without a central landing marker or an import's guess. */
    public static final Vec3 DEFAULT_ENTRY = new Vec3(0.5, 65, 0.5);
    /** The smallest build range the toolbox shrinks to. */
    public static final int MIN_DIAMETER = 16;

    /** How a new draft starts (design 11.1, 19). */
    public sealed interface Seed {
        record CurrentLobby() implements Seed {}
        record Blank() implements Seed {}
        /** A {@code .hrlobby} in the pack library, by file name. */
        record Pack(String fileName) implements Seed {}
        /** An entry of {@code hardrush/workshop/import}, by name: a save or a building file (4-level operators). */
        record Import(String name) implements Seed {}
    }

    private static final class Open {
        final RuntimeLevelHandle handle;
        int idle;
        boolean closing;
        Open(RuntimeLevelHandle handle) { this.handle = handle; }
    }

    private final MinecraftServer server;
    private final PlayerProfiles profiles;
    private final WorkshopStorage storage;
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "hardrush-workshop");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<WorkshopStorage.Listing> loading;
    private String failure;
    private final Map<String, Draft> drafts = new TreeMap<>();
    private final Map<String, Open> open = new HashMap<>();
    private final Map<String, DraftImport> imports = new HashMap<>();
    /** Who started each import, told when it is ready. */
    private final Map<String, UUID> importers = new HashMap<>();
    /** Ids never handed out again this session: deleted drafts (Fantasy may still be removing their dimension) and unreadable ones. */
    private final Set<String> retired = new HashSet<>();
    final ButtonMenu.Throttle throttle = new ButtonMenu.Throttle();
    private final ImportFolder importFolder;
    private final WorkshopChecks checks = new WorkshopChecks();
    private final MarkerView view = new MarkerView();
    private final Map<String, PublishJob> publishes = new HashMap<>();
    /** Building files being pasted into a draft, and who asked. */
    private final Map<String, DraftImport> pastes = new HashMap<>();
    private final Map<String, UUID> pasters = new HashMap<>();
    /** Builders without their toolbox, and for how many ticks. */
    private final Map<UUID, Integer> missingToolbox = new HashMap<>();

    private Workshop(MinecraftServer server, PlayerProfiles profiles) {
        this.server = server;
        this.profiles = profiles;
        var root = server.getWorldPath(LevelResource.ROOT);
        storage = new WorkshopStorage(root);
        importFolder = new ImportFolder(root);
        var loaded = new HashSet<String>();
        for (var level : server.getAllLevels())
            if (level.dimension().identifier().getNamespace().equals(LobbyDimension.NAMESPACE)) loaded.add(level.dimension().identifier().getPath());
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                var listing = storage.list();
                var keep = new HashSet<>(listing.directories());
                var drafts = new TreeMap<>(listing.drafts());
                // An import cut short by a stop or crash left a partial world: start over rather than offer it.
                for (var draft : listing.drafts().values()) {
                    if (draft.state() != Draft.State.IMPORTING || loaded.contains(LobbyDimension.WORKSHOP_PREFIX + draft.id())) continue;
                    LOGGER.warn("草稿 {} 的导入没有完成，删除", draft.id());
                    storage.delete(draft.id());
                    keep.remove(draft.id());
                    drafts.remove(draft.id());
                }
                storage.cleanup(keep, loaded);
                return new WorkshopStorage.Listing(drafts, keep);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, io);
    }

    public static void configure(MinecraftServer server, PlayerProfiles profiles) {
        if (!server.isSameThread()) throw new IllegalStateException("Workshop starts on the server thread");
        SERVICES.put(server, new Workshop(server, profiles));
    }
    public static Optional<Workshop> get(MinecraftServer server) { return Optional.ofNullable(SERVICES.get(server)); }

    /**
     * {@code /hardrush lobby} for 2-level operators: the workshop menu, in the lobby or inside a draft. Players reach the
     * same menu through the camp's {@link WorkshopTable}; the command stays for consoles and scripts.
     */
    public static void registerCommands() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("hardrush").then(Commands.literal("lobby")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var workshop = SERVICES.get(context.getSource().getServer());
                            if (workshop == null) {
                                context.getSource().sendFailure(net.minecraft.network.chat.Component.literal("工坊只在比赛模式下开放。"));
                                return 0;
                            }
                            return workshop.openMenu(player) ? 1 : 0;
                        }))));
        // Breaking a bound block takes the markers bound to it along (design 4.4 item 5).
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel serverLevel && LobbyDimension.isWorkshop(serverLevel)) Toolkit.blockBroken(serverLevel, pos);
        });
    }

    // ---- Queries -------------------------------------------------------------------------------------------------

    public static boolean gamemaster(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }
    public static boolean owner(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(player.createCommandSourceStack());
    }

    /** Drafts by id; empty until the startup listing is read. */
    public Collection<Draft> drafts() { return List.copyOf(drafts.values()); }
    public Optional<Draft> draft(String id) { return Optional.ofNullable(drafts.get(id)); }
    public boolean loaded() { return loading == null && failure == null; }
    public Optional<String> failure() { return Optional.ofNullable(failure); }
    /** What the draft's import is doing, while it runs. */
    public Optional<String> importProgress(String id) { return Optional.ofNullable(imports.get(id)).map(DraftImport::progress); }
    /** Online builders inside {@code id}. */
    public List<ServerPlayer> builders(String id) {
        var key = LobbyDimension.workshopKey(id);
        return server.getPlayerList().getPlayers().stream().filter(p -> profiles.buildingLevel(p).filter(key::equals).isPresent()).toList();
    }
    /** The draft a builder is assigned to. */
    public Optional<Draft> buildingIn(ServerPlayer player) {
        return profiles.buildingLevel(player).flatMap(key -> draftId(key)).map(drafts::get);
    }
    /** Where builders arrive in a draft: its first central landing marker, else what the import suggested. */
    public Vec3 entryPoint(Draft draft) {
        var at = draft.entry();
        return at == null ? DEFAULT_ENTRY : new Vec3(at.x(), at.y(), at.z());
    }
    MinecraftServer server() { return server; }
    WorkshopChecks checks() { return checks; }
    /** What {@code hardrush/workshop/import} holds right now. */
    List<ImportFolder.Entry> importEntries() { return importFolder.list(); }
    /** The library's {@code .hrlobby} files, by name. */
    List<String> libraryPacks() {
        var packs = LobbyStorage.forWorld(server.getWorldPath(LevelResource.ROOT)).packs();
        if (!java.nio.file.Files.isDirectory(packs, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var list = java.nio.file.Files.list(packs)) {
            return list.filter(p -> java.nio.file.Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(p -> p.getFileName().toString()).filter(n -> n.endsWith(dev.hardrush.core.lobby.plan.PackManifest.EXTENSION)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
    Optional<PublishJob> publishJob(String id) { return Optional.ofNullable(publishes.get(id)); }
    Optional<String> pasteProgress(String id) { return Optional.ofNullable(pastes.get(id)).map(DraftImport::progress); }

    /** Test seam: runs the light check of {@code id} now. */
    public Optional<List<dev.hardrush.core.lobby.plan.Problem>> checkNow(String id) {
        var draft = drafts.get(id);
        var level = draft == null ? null : level(id).orElse(null);
        return level == null ? Optional.empty() : Optional.of(checks.check(this, draft, level).problems());
    }
    /** Test seam: the stage of the draft's publish job and the pack it wrote, if any. */
    public Optional<String> publishState(String id) {
        return Optional.ofNullable(publishes.get(id)).map(job -> job.stage().name() + (job.written() == null ? "" : " " + job.written())
                + (job.errors().isEmpty() ? "" : " " + job.errors()));
    }
    /** The draft's dimension while it is loaded. */
    public Optional<ServerLevel> level(String id) {
        var o = open.get(id);
        return o == null || o.closing ? Optional.empty() : Optional.of(o.handle.asLevel());
    }

    public static Optional<String> draftId(ResourceKey<Level> key) {
        if (!key.identifier().getNamespace().equals(LobbyDimension.NAMESPACE)) return Optional.empty();
        return LobbyDimension.workshopDraft(key.identifier().getPath());
    }

    /**
     * Why a builder must go back to the lobby now (design 11.3), or empty. Checked every tick by {@link #tick} (which
     * tells the builder) and again by the profile service before it touches the body.
     */
    public static Optional<String> leaveReason(ServerPlayer player) {
        var server = player.level().getServer();
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(server)) return Optional.of("正在保存比赛进度");
        if (!gamemaster(player)) return Optional.of("你已没有建造权限");
        var refusal = dev.hardrush.round.RoundRuntime.buildingRefusal(player);
        if (refusal.isPresent()) return refusal;
        var workshop = SERVICES.get(server);
        if (workshop == null) return Optional.of("工坊已关闭");
        var id = PlayerProfiles.of(server).flatMap(p -> p.buildingLevel(player)).flatMap(Workshop::draftId);
        if (workshop.loaded() && (id.isEmpty() || !workshop.drafts.containsKey(id.get()))) return Optional.of("草稿已被删除");
        return Optional.empty();
    }

    // ---- Actions -------------------------------------------------------------------------------------------------

    public boolean openMenu(ServerPlayer player) {
        // The first look at an empty workshop starts the default draft from the lobby everybody knows (design 11.1).
        if (loaded() && drafts.isEmpty() && gamemaster(player)) create(player, new Seed.CurrentLobby());
        return WorkshopMenu.open(this, player);
    }

    /** Starts a new draft; returns why it cannot. */
    public Optional<String> create(ServerPlayer actor, Seed seed) {
        if (!gamemaster(actor)) return Optional.of("需要 2 级管理员权限");
        if (!loaded()) return Optional.of(failure != null ? "工坊读取出错，请查看日志" : "工坊正在读取，稍等片刻");
        if (drafts.size() >= MAX_DRAFTS) return Optional.of("草稿已满 " + MAX_DRAFTS + " 个，先删除一个");
        var id = nextId();
        String name, source;
        DraftImport.Loader loader;
        switch (seed) {
            case Seed.CurrentLobby ignored -> {
                var lobby = LobbyWorld.open(server);
                var installed = lobby.installation().orElse(null);
                if (installed == null) return Optional.of("大厅还没准备好");
                var copy = LobbyStorage.forWorld(server.getWorldPath(LevelResource.ROOT)).installed(installed.seq());
                name = installed.pack().name();
                source = "当前大厅 " + installed.pack().name() + " v" + installed.pack().version();
                loader = DraftImport.pack(LobbyInstall.Source.file(copy), HardRushProperties.lobbyMaxSize());
            }
            case Seed.Blank ignored -> {
                name = "空白草稿";
                source = "空白";
                loader = DraftImport.blank();
            }
            case Seed.Pack pack -> {
                // Names come back from menu buttons: only an exact library entry is accepted.
                if (!libraryPacks().contains(pack.fileName())) return Optional.of("包库里没有 " + pack.fileName());
                var file = LobbyStorage.forWorld(server.getWorldPath(LevelResource.ROOT)).packs().resolve(pack.fileName());
                name = displayName(pack.fileName().substring(0, pack.fileName().length() - dev.hardrush.core.lobby.plan.PackManifest.EXTENSION.length()));
                source = "包库 " + pack.fileName();
                loader = DraftImport.pack(LobbyInstall.Source.file(file), HardRushProperties.lobbyMaxSize());
            }
            case Seed.Import imported -> {
                // Reading arbitrary files the owner put on disk is 4-level (design 19).
                if (!owner(actor)) return Optional.of("从存档或建筑文件新建需要 4 级管理员权限");
                var entry = importFolder.find(imported.name()).orElse(null);
                if (entry == null) return Optional.of("import 目录里没有 " + imported.name());
                name = displayName(entry.name());
                boolean save = entry.kind() == ImportFolder.Kind.SAVE;
                source = (save ? "存档 " : "建筑文件 ") + entry.name();
                loader = save ? DraftImport.save(entry.path(), storage.dimension(id))
                        : DraftImport.structure(entry.path(), HardRushProperties.lobbyMaxSize(), DraftImport::centredOnOrigin);
            }
        }
        var draft = Draft.create(id, name, source, HardRushProperties.lobbyMaxSize());
        drafts.put(id, draft);
        importers.put(id, actor.getUUID());
        persist(draft);
        imports.put(id, new DraftImport(storage.dimension(id), loader, () -> openLevel(drafts.get(id))));
        LOGGER.info("{} 新建草稿 {}（{}）", actor.getScoreboardName(), id, source);
        return Optional.empty();
    }

    /** Enters {@code player} into the draft as a builder (design 11.2); returns why it cannot. */
    public Optional<String> enter(ServerPlayer player, String id) {
        var draft = drafts.get(id);
        if (draft == null) return Optional.of("草稿不存在了");
        if (draft.state() != Draft.State.READY) return Optional.of("草稿还在导入，稍等片刻");
        if (!gamemaster(player)) return Optional.of("需要 2 级管理员权限");
        if (profiles.buildingLevel(player).isPresent()) return Optional.of("你已经在工坊里了，先离开当前草稿");
        var refusal = dev.hardrush.round.RoundRuntime.buildingRefusal(player);
        if (refusal.isPresent()) return refusal;
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(server)) return Optional.of("正在保存比赛进度，稍后再试");
        if (!LobbyWorld.open(server).ready() || !PlayerProfiles.verifiedLobbyBody(player)) return Optional.of("回到营地站稳后再进入工坊");
        var level = openLevel(draft);
        if (level == null) return Optional.of("草稿正在保存，稍后再试");
        if (!profiles.startBuilding(player, level, entryPoint(draft))) return Optional.of("现在不能进入工坊");
        Toolkit.give(player);
        var updated = draft.withParticipant(player.getUUID(), player.getScoreboardName());
        if (updated != draft) { drafts.put(id, updated); persist(updated); }
        LOGGER.info("{} 进入草稿 {}", player.getScoreboardName(), id);
        return Optional.empty();
    }

    /** Sends a builder back to the lobby; {@code reason} is told to them when not null. */
    public void leave(ServerPlayer player, String reason) {
        missingToolbox.remove(player.getUUID());
        if (!profiles.stopBuilding(player)) return;
        if (reason != null) Notices.personal(player, reason);
    }

    /** Deletes a draft (4-level operators); its builders go back first. Returns why it cannot. */
    public Optional<String> delete(ServerPlayer actor, String id) {
        if (!owner(actor)) return Optional.of("需要 4 级管理员权限");
        var draft = drafts.get(id);
        if (draft == null) return Optional.of("草稿不存在了");
        for (var builder : builders(id)) leave(builder, "草稿 " + Notices.key(draft.name()) + " 已被删除 · 已回到营地");
        discard(id);
        LOGGER.info("{} 删除草稿 {}", actor.getScoreboardName(), id);
        return Optional.empty();
    }

    // ---- Toolbox actions (design 11.1, 19.1) ---------------------------------------------------------------------

    /** Moves the build range's centre to the builder. */
    public Optional<String> setCenter(ServerPlayer player) {
        return changeDraft(player, d -> d.withBorder(player.getBlockX(), player.getBlockZ(), d.borderDiameter()));
    }

    /** Grows or shrinks the build range by {@code delta} blocks of diameter. */
    public Optional<String> resize(ServerPlayer player, int delta) {
        int max = HardRushProperties.lobbyMaxSize();
        return changeDraft(player, d -> {
            int size = Math.max(MIN_DIAMETER, Math.min(max, d.borderDiameter() + delta));
            return size == d.borderDiameter() ? null : d.withBorder(d.borderCenterX(), d.borderCenterZ(), size);
        });
    }

    /** Leaves the blocks below {@code y} out of publishing ({@code y} itself is kept); null publishes every height again. */
    public Optional<String> setBottom(ServerPlayer player, Integer y) { return changeDraft(player, d -> d.withBottomY(y)); }

    /**
     * The lowest layer "bottom height at my feet" keeps: the block the builder stands on (a slab or carpet counts as
     * that block), not the feet block above it, so the floor itself is published.
     */
    public static int bottomUnderfoot(ServerPlayer player) { return player.getOnPos().getY(); }

    private Optional<String> changeDraft(ServerPlayer player, java.util.function.UnaryOperator<Draft> change) {
        var draft = buildingIn(player).orElse(null);
        if (draft == null || !PlayerProfiles.building(player)) return Optional.of("只能在工坊里调整");
        var updated = change.apply(draft);
        if (updated == null) return Optional.of(draft.borderDiameter() <= MIN_DIAMETER ? "范围已经最小" : "范围已经最大");
        drafts.put(draft.id(), updated);
        persist(updated);
        level(draft.id()).ifPresent(level -> {
            level.getWorldBorder().setCenter(updated.borderCenterX(), updated.borderCenterZ());
            level.getWorldBorder().setSize(updated.borderDiameter());
        });
        return Optional.empty();
    }

    /**
     * Removes every loaded non-player entity inside the build range that installing would drop anyway (design 19.1):
     * saves bring their animals and mobs. Decorations on the entity whitelist stay. Returns how many went.
     */
    public Optional<String> clearMobs(ServerPlayer player, java.util.function.IntConsumer removedCount) {
        var draft = buildingIn(player).orElse(null);
        if (draft == null || !PlayerProfiles.building(player)) return Optional.of("只能在工坊里清除生物");
        if (publishing(draft.id())) return Optional.of(PUBLISHING);
        var level = (ServerLevel) player.level();
        int removed = 0;
        for (var entity : toList(level.getAllEntities())) {
            if (entity instanceof net.minecraft.world.entity.player.Player || entity.isRemoved()) continue;
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (dev.hardrush.lobby.pack.TileSanitizer.ALLOWED_ENTITIES.contains(type)) continue;
            if (!level.getWorldBorder().isWithinBounds(entity.blockPosition())) continue;
            entity.discard();
            removed++;
        }
        removedCount.accept(removed);
        return Optional.empty();
    }
    private static List<net.minecraft.world.entity.Entity> toList(Iterable<net.minecraft.world.entity.Entity> entities) {
        var out = new ArrayList<net.minecraft.world.entity.Entity>();
        entities.forEach(out::add);
        return out;
    }

    /**
     * Pastes a building file from the import folder into the builder's draft (design 19.2): centred horizontally on
     * them, bottom face at their feet, air never overwriting anything. 2-level; no undo, the menu asks first.
     */
    public Optional<String> paste(ServerPlayer player, String name) {
        var draft = buildingIn(player).orElse(null);
        if (draft == null || !PlayerProfiles.building(player)) return Optional.of("只能在工坊里贴入");
        if (!gamemaster(player)) return Optional.of("需要 2 级管理员权限");
        if (pastes.containsKey(draft.id())) return Optional.of("这个草稿正在贴入另一个建筑");
        if (publishing(draft.id())) return Optional.of(PUBLISHING);
        var entry = importFolder.find(name).filter(e -> e.kind() == ImportFolder.Kind.STRUCTURE).orElse(null);
        if (entry == null) return Optional.of("import 目录里没有建筑文件 " + name);
        var level = (ServerLevel) player.level();
        int x = player.getBlockX(), y = player.getBlockY(), z = player.getBlockZ();
        var loader = DraftImport.structure(entry.path(), HardRushProperties.lobbyMaxSize(),
                s -> new dev.hardrush.core.lobby.plan.Cell(x - s.sizeX() / 2, y, z - s.sizeZ() / 2));
        pastes.put(draft.id(), new DraftImport(null, loader, () -> level(draft.id()).orElse(null)));
        pasters.put(draft.id(), player.getUUID());
        LOGGER.info("{} 往草稿 {} 贴入 {}（{} {} {}）", player.getScoreboardName(), draft.id(), name, x, y, z);
        return Optional.empty();
    }

    // ---- Publishing (design 11.5) --------------------------------------------------------------------------------

    private static final String PUBLISHING = "草稿正在发布，等发布完成再操作";

    /**
     * A publish job of {@code id} has not finished. It scans and then captures tile by tile, so the mod's own bulk
     * world edits (pasting, clearing mobs) must wait: they would leave half a building in the pack or move what the
     * scan accepted. A builder's hand edits cannot be held back and are their own business.
     */
    private boolean publishing(String id) {
        var job = publishes.get(id);
        return job != null && job.stage() != PublishJob.Stage.DONE && job.stage() != PublishJob.Stage.FAILED;
    }

    /** Starts publishing a draft (4-level operators); returns why it cannot. */
    public Optional<String> publish(ServerPlayer actor, String id) {
        if (!owner(actor)) return Optional.of("需要 4 级管理员权限");
        var draft = drafts.get(id);
        if (draft == null) return Optional.of("草稿不存在了");
        if (draft.state() != Draft.State.READY) return Optional.of("草稿还在导入，稍等片刻");
        var running = publishes.get(id);
        if (publishing(id)) return Optional.of("这个草稿正在发布");
        if (pastes.containsKey(id)) return Optional.of("草稿正在贴入建筑，完成后再发布");
        var level = openLevel(draft);
        if (level == null) return Optional.of("草稿正在保存，稍后再试");
        if (running != null) StatusBars.hideEverywhere(server, running.bar);
        publishes.put(id, new PublishJob(draft, actor.getUUID(), level, HardRushProperties.lobbyMaxSize()));
        LOGGER.info("{} 开始发布草稿 {}", actor.getScoreboardName(), id);
        return Optional.empty();
    }

    /** The publisher strikes a participant from the authors, or puts them back. */
    public void toggleAuthor(ServerPlayer actor, String id, UUID participant) {
        var job = publishes.get(id);
        if (job == null || job.stage() != PublishJob.Stage.CONFIRM || !owner(actor)) return;
        if (!job.excluded.remove(participant)) job.excluded.add(participant);
    }

    /** Writes the pack once the publisher confirmed the authors. */
    public Optional<String> confirmPublish(ServerPlayer actor, String id) {
        if (!owner(actor)) return Optional.of("需要 4 级管理员权限");
        var job = publishes.get(id);
        var draft = drafts.get(id);
        if (job == null || draft == null || job.stage() != PublishJob.Stage.CONFIRM) return Optional.of("没有等待确认的发布");
        job.confirm(draft, LobbyStorage.forWorld(server.getWorldPath(LevelResource.ROOT)).packs(), io);
        return Optional.empty();
    }

    /** Drops a finished, failed or unconfirmed publish job. */
    public void dismissPublish(String id) {
        var job = publishes.get(id);
        if (job == null || job.stage() == PublishJob.Stage.WRITING) return;
        publishes.remove(id);
        job.cancel();
        StatusBars.hideEverywhere(server, job.bar);
    }

    /** The light check found the draft's first central landing: builders arrive there from now on. */
    void updateEntry(String id, dev.hardrush.core.lobby.plan.Point entry) {
        var draft = drafts.get(id);
        if (draft == null) return;
        var updated = draft.withEntry(entry);
        drafts.put(id, updated);
        persist(updated);
    }

    /** Short names for drafts made from files: the file name without its extension, at most 32 characters. */
    private static String displayName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        var base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.length() > 32 ? base.substring(0, 32) : base;
    }

    // ---- Lifecycle -----------------------------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        var workshop = SERVICES.get(server);
        if (workshop != null) workshop.tick();
    }

    private void tick() {
        throttle.expire();
        if (loading != null) {
            if (!loading.isDone()) return;
            try {
                var listing = loading.join();
                drafts.putAll(listing.drafts());
                for (var id : listing.directories()) if (!listing.drafts().containsKey(id)) retired.add(id);
            } catch (RuntimeException error) {
                LOGGER.error("工坊草稿读取失败", error);
                failure = "工坊草稿读取失败：" + error.getMessage();
            }
            loading = null;
        }
        tickImports();
        tickPastes();
        tickPublishes();
        tickBuilders();
        WorkshopTable.tick(server);
        checks.tick(this, server.getTickCount());
        view.tick(this, server.getTickCount());
        tickDimensions();
    }

    private void tickPastes() {
        for (var id : List.copyOf(pastes.keySet())) {
            var job = pastes.get(id);
            var paster = pasters.get(id);
            var player = paster == null ? null : server.getPlayerList().getPlayer(paster);
            boolean done;
            try { done = job.tick(); }
            catch (RuntimeException error) {
                LOGGER.warn("草稿 {} 贴入建筑失败", id, error);
                job.cancel();
                pastes.remove(id); pasters.remove(id);
                if (player != null) Notices.personal(player, "贴入建筑失败：" + error.getMessage());
                continue;
            }
            if (!done) continue;
            pastes.remove(id); pasters.remove(id);
            if (player != null) {
                var notes = job.content().notes();
                Notices.personal(player, "建筑已贴入" + (notes.isEmpty() ? "" : " · " + String.join("；", notes)));
            }
        }
    }

    private void tickPublishes() {
        for (var entry : List.copyOf(publishes.entrySet())) {
            var id = entry.getKey();
            var job = entry.getValue();
            var before = job.stage();
            try { job.tick(); }
            catch (RuntimeException error) {
                LOGGER.error("草稿 {} 发布出错", id, error);
                job.cancel();
            }
            var stage = job.stage();
            var publisher = server.getPlayerList().getPlayer(job.publisher);
            boolean running = stage != PublishJob.Stage.DONE && stage != PublishJob.Stage.FAILED;
            job.bar.setName(net.minecraft.network.chat.Component.literal("发布「" + draft(id).map(Draft::name).orElse(id) + "」· " + job.progress()));
            job.bar.setProgress(job.fraction());
            if (publisher != null) {
                if (running) StatusBars.show(publisher, StatusBars.Slot.PERSONAL, job.bar);
                else StatusBars.hide(publisher, StatusBars.Slot.PERSONAL);
            }
            if (stage == before) continue;
            var name = Notices.key(draft(id).map(Draft::name).orElse(id));
            switch (stage) {
                case CONFIRM -> {
                    if (publisher != null) Notices.personal(publisher, "草稿 " + name + " 已打包好 · 在「大厅工坊」的草稿页确认作者后写入"
                            + (job.warnings().isEmpty() ? "" : "\n提醒：\n· " + String.join("\n· ", PublishJob.shown(job.warnings()))));
                }
                case DONE -> {
                    var file = job.written().getFileName().toString();
                    var version = file.substring(file.lastIndexOf("-v") + 2, file.length() - dev.hardrush.core.lobby.plan.PackManifest.EXTENSION.length());
                    draft(id).ifPresent(d -> {
                        var updated = d.published(Integer.parseInt(version));
                        drafts.put(id, updated);
                        persist(updated);
                    });
                    LOGGER.info("草稿 {} 已发布为 {}", id, file);
                    if (publisher != null) Notices.personal(publisher, "草稿 " + name + " 已发布为 " + Notices.key(file) + " · 草稿页可以「现在安装」");
                }
                case FAILED -> {
                    if (publisher != null) Notices.personal(publisher, "草稿 " + name + " 没能发布：\n· " + String.join("\n· ", PublishJob.shown(job.errors())));
                }
                default -> { }
            }
        }
    }

    private void tickImports() {
        for (var id : List.copyOf(imports.keySet())) {
            var job = imports.get(id);
            boolean done;
            try { done = job.tick(); }
            catch (RuntimeException error) {
                LOGGER.warn("草稿 {} 导入失败", id, error);
                var draft = drafts.get(id);
                var importer = importers.get(id);
                var player = importer == null ? null : server.getPlayerList().getPlayer(importer);
                discard(id);
                if (player != null) Notices.personal(player, "草稿 " + Notices.key(draft == null ? id : draft.name()) + " 没能建好：" + error.getMessage());
                continue;
            }
            if (!done) continue;
            imports.remove(id);
            var content = job.content();
            var ready = drafts.get(id).ready();
            if (content.center() != null) {
                ready = ready.withBorder(content.center().x(), content.center().z(), DraftImport.diameterFor(ready.borderDiameter(), content.bounds(), HardRushProperties.lobbyMaxSize()));
                var border = level(id).map(ServerLevel::getWorldBorder).orElse(null);
                if (border != null) {
                    border.setCenter(content.center().x(), content.center().z());
                    border.setSize(ready.borderDiameter());
                }
            }
            if (content.entry() != null) ready = ready.withEntry(content.entry());
            drafts.put(id, ready);
            persist(ready);
            var importer = importers.remove(id);
            var player = importer == null ? null : server.getPlayerList().getPlayer(importer);
            if (player != null) Notices.personal(player, "草稿 " + Notices.key(ready.name()) + " 已建好 · 右键营地里的「大厅工坊」制图台进入"
                    + (content.notes().isEmpty() ? "" : "\n· " + String.join("\n· ", content.notes())));
        }
    }

    /** Drops a draft: its import, its dimension and its files. Builders must have left. */
    private void discard(String id) {
        drafts.remove(id);
        retired.add(id);
        importers.remove(id);
        var job = imports.remove(id);
        if (job != null) job.cancel();
        var paste = pastes.remove(id);
        pasters.remove(id);
        if (paste != null) paste.cancel();
        var publish = publishes.remove(id);
        if (publish != null) {
            publish.cancel();
            StatusBars.hideEverywhere(server, publish.bar);
            var publisher = server.getPlayerList().getPlayer(publish.publisher);
            if (publisher != null && publish.stage() != PublishJob.Stage.DONE) Notices.personal(publisher, "草稿已被删除，发布已取消");
        }
        checks.forget(server, id);
        var o = open.remove(id);
        boolean registered = server.getLevel(LobbyDimension.workshopKey(id)) != null;
        if (registered && o != null) o.handle.delete();
        // Fantasy deletes a registered dimension's files itself; ours are only the draft's own then.
        io.execute(() -> {
            try {
                if (registered) storage.deleteDraftDirectory(id); else storage.delete(id);
            } catch (IOException e) { LOGGER.error("删除草稿 {} 的文件失败", id, e); }
        });
    }

    private void tickBuilders() {
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            var key = profiles.buildingLevel(player).orElse(null);
            if (key == null) continue;
            var reason = leaveReason(player);
            if (reason.isPresent()) {
                leave(player, reason.get() + " · 已回到营地");
                continue;
            }
            if (PlayerProfiles.building(player)) Toolkit.keep(player, missingToolbox);
            // Nobody dies in a workshop; a fall into the void ends back at the entry.
            if (player.level().dimension().equals(key) && player.getY() < player.level().getMinY() - VOID_MARGIN) {
                var draft = buildingIn(player).orElse(null);
                var at = draft == null ? DEFAULT_ENTRY : entryPoint(draft);
                player.teleportTo(player.level(), at.x, at.y, at.z, Set.of(), player.getYRot(), player.getXRot(), true);
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
            }
        }
    }

    /** A job still reads or writes the draft's world, so it stays open without builders. */
    private boolean busy(String id) {
        var publish = publishes.get(id);
        return imports.containsKey(id) || pastes.containsKey(id) || publish != null && switch (publish.stage()) {
            case LOADING, SCANNING, CAPTURING -> true;
            default -> false;
        };
    }

    private void tickDimensions() {
        for (var it = open.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var o = entry.getValue();
            var level = o.handle.asLevel();
            if (o.closing) {
                if (server.getLevel(level.dimension()) == null) it.remove();
                continue;
            }
            if (busy(entry.getKey()) || !level.players().isEmpty()) { o.idle = 0; continue; }
            // Saved as soon as the last builder leaves; unloaded once it stays empty.
            if (o.idle++ == 0) level.save(null, true, false);
            if (o.idle >= IDLE_UNLOAD_TICKS) {
                o.closing = true;
                o.handle.unload();
            }
        }
    }

    /** Before the profile service seals bodies on stop: every builder goes back; unfinished imports restart next time. */
    public static void stop(MinecraftServer server) {
        var workshop = SERVICES.remove(server);
        if (workshop == null) return;
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) {
            try { workshop.profiles.stopBuilding(player); }
            catch (RuntimeException error) { LOGGER.error("停服时送回建设者 {} 失败", player.getScoreboardName(), error); }
        }
        for (var job : workshop.imports.values()) job.cancel();
        for (var job : workshop.pastes.values()) job.cancel();
        for (var job : workshop.publishes.values()) { job.cancel(); StatusBars.hideEverywhere(server, job.bar); }
        workshop.io.shutdown();
        try {
            if (!workshop.io.awaitTermination(10, TimeUnit.SECONDS)) LOGGER.warn("工坊文件写入未在停服前完成");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---- Internals -----------------------------------------------------------------------------------------------

    private String nextId() {
        for (int n = 1; ; n++) {
            var id = "draft" + n;
            if (!drafts.containsKey(id) && !retired.contains(id)) return id;
        }
    }

    private void persist(Draft draft) {
        io.execute(() -> {
            try { storage.write(draft); }
            catch (IOException e) {
                LOGGER.error("保存草稿 {} 失败", draft.id(), e);
                server.execute(() -> Notices.operators(server, "保存草稿 " + draft.id() + " 失败：" + e.getMessage()));
            }
        });
    }

    /** Opens the draft's dimension with its border and entry; null while an unload of it is still under way. */
    private ServerLevel openLevel(Draft draft) {
        var o = open.get(draft.id());
        var key = LobbyDimension.workshopKey(draft.id());
        if (o != null && o.closing) {
            // Reopening while Fantasy's queued unload is pending would let that unload run on the reopened level.
            if (server.getLevel(key) != null) return null;
            open.remove(draft.id());
            o = null;
        }
        if (o == null) {
            var handle = LobbyDimension.open(server, key);
            var level = handle.asLevel();
            var border = level.getWorldBorder();
            border.setCenter(draft.borderCenterX(), draft.borderCenterZ());
            border.setSize(draft.borderDiameter());
            LobbyDimension.spawn(level, BlockPos.containing(entryPoint(draft)));
            o = new Open(handle);
            open.put(draft.id(), o);
        }
        o.idle = 0;
        return o.handle.asLevel();
    }
}
