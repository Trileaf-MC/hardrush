package dev.hardrush.player;

import dev.hardrush.HardRushProperties;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.player.mixin.PlayerProfileResetAccessor;
import dev.hardrush.player.mixin.PlayerCooldownAccessor;
import dev.hardrush.player.mixin.PlayerAdvancementProfileAccessor;
import dev.hardrush.player.mixin.PlayerRespawnConnectionAccessor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main-thread player quarantine with a separate archive worker and generation-tagged callbacks. */
public final class PlayerProfiles implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-profiles");
    private static final Map<MinecraftServer, PlayerProfiles> SERVICES = new IdentityHashMap<>();
    private static final ThreadLocal<PlayerProfiles> MONSTER_RESPAWN = new ThreadLocal<>();
    private static final ThreadLocal<net.minecraft.world.level.portal.TeleportTransition> CELEBRATION_RESPAWN = new ThreadLocal<>();
    private static final String FRESH = "hardrush_fresh_profile";
    private static final String SUSPENSION_ID = "hardrush_suspension_id";
    private final MinecraftServer server;
    private final Supplier<ServerLevel> lobby;
    private final Supplier<Vec3> lobbySpawn;
    private final Map<UUID, Entry> players = new LinkedHashMap<>();
    private final ExecutorService io;
    private CompletableFuture<ProfileJournal> journal;
    private final Path journalRoot;
    private boolean recoveryPaused;
    private boolean closed;
    private volatile Throwable ioFailure;
    private final java.util.concurrent.Executor callbacks;

    public PlayerProfiles(MinecraftServer server, Path root, Supplier<ServerLevel> lobby, Supplier<Vec3> lobbySpawn) {
        this(server, root, lobby, lobbySpawn, dev.hardrush.round.MainThreadExecutor.forServer(server));
    }

    /** Explicit callback executor; durable IO is still owned and drained by this profile service. */
    public PlayerProfiles(MinecraftServer server, Path root, Supplier<ServerLevel> lobby, Supplier<Vec3> lobbySpawn,
            java.util.concurrent.Executor callbacks) {
        this.server = server;
        this.journalRoot = root;
        this.lobby = lobby;
        this.lobbySpawn = lobbySpawn;
        this.callbacks = java.util.Objects.requireNonNull(callbacks);
        io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "hardrush-profiles");
            thread.setDaemon(true);
            return thread;
        });
        journal = supply(() -> new ProfileJournal(root));
    }

    public static void install(MinecraftServer server, PlayerProfiles service) {
        if (SERVICES.putIfAbsent(server, service) != null) throw new IllegalStateException("Profile service already installed");
    }

    public static void uninstall(MinecraftServer server) { SERVICES.remove(server); }
    public static Optional<PlayerProfiles> of(MinecraftServer server) { return Optional.ofNullable(SERVICES.get(server)); }

    /** HEAD of placeNewPlayer: before the login packet and before player/world tick or gameplay packets. */
    public static void beforeLogin(ServerPlayer player) {
        if (!HardRushProperties.competitionEnabled()) return;
        var service = SERVICES.get(player.level().getServer());
        if (service != null) service.onLogin(player);
        else {
            // Initialization failure must never fall back to entering the saved competition world.
            ((ProfileTagged) player).hardrush$isolated(true);
            player.stopRiding();
            var server = player.level().getServer();
            var level = server.overworld();
            player.setServerLevel(level);
            var spawn = level.getRespawnData().pos();
            player.setPos(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5);
        }
    }

    public static void afterLogin(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        if (service != null) service.onConnected(player);
    }

    public static boolean isLocked(ServerPlayer player) {
        if (dev.hardrush.participation.ParticipationControl.pending(player)) return true;
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer())) return true;
        if (!HardRushProperties.competitionEnabled()) return false;
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (entry == null || entry.player != player) return ((ProfileTagged) player).hardrush$isolated();
        return !entry.admitted || entry.deathHeld;
    }

    public static boolean canMoveInLobby(ServerPlayer player) {
        if (dev.hardrush.participation.ParticipationControl.pending(player)) return false;
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer())) return false;
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && !service.closed && !entry.admitted
                && (!dev.hardrush.participation.ParticipationControl.disabled(player.level().getServer(), player.getUUID()) || entry.exclusionInitialized)
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit
                && player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                && dev.hardrush.round.RoundRuntime.lobbyActivityAllowed(player);
    }

    /** A verified, safe lobby body may read rules even while the competition is quarantined. */
    public static boolean verifiedLobbyBody(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && !service.closed && !entry.admitted && !entry.adminSpectating
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit
                && entry.ready && entry.problem.isEmpty() && entry.diskSafe && player.isAlive()
                && dev.hardrush.world.LobbyWorld.isLobby(player.level());
    }

    /**
     * The round-world counterpart of {@link #verifiedLobbyBody}: an admitted, verified and unlocked human body standing
     * in a formal round world, i.e. the body whose death {@code AFTER_DEATH} would capture. The caller still matches
     * the life sequence against the committed participant.
     */
    public static boolean verifiedRoundBody(ServerPlayer player) {
        return player.isAlive() && verifiedDyingRoundBody(player);
    }

    /**
     * {@link #verifiedRoundBody} for a body inside its lethal hit (health already at zero, so not "alive"), before
     * {@code AFTER_DEATH} holds its death: the admitted, verified, unlocked round body in every other respect.
     */
    public static boolean verifiedDyingRoundBody(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && !service.closed && entry.admitted && !entry.adminSpectating
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit && !entry.celebrationGuest
                && !entry.building && !entry.observing
                && entry.ready && entry.problem.isEmpty() && entry.diskSafe && !isLocked(player)
                && !player.isRemoved() && !player.isSpectator() && dev.hardrush.world.RoundWorldBundle.owns(player.level());
    }

    public static boolean simulationFrozen(ServerPlayer player) {
        if (dev.hardrush.participation.ParticipationControl.pending(player)) return true;
        if (dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer())) return true;
        return isLocked(player) && !canMoveInLobby(player) && !isAdministrativeSpectator(player) && !observing(player)
                && !building(player);
    }

    /**
     * A verified lobby body building in its own workshop draft (design 11.2). It is still locked (never a round body,
     * no advancements), but the isolation gates let it act: see {@link #sealed}.
     */
    public static boolean building(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return administrativeBody(service, entry, player) && entry.building
                && player.level().dimension().equals(entry.buildingLevel);
    }

    /** What the isolation gates block: every locked body except a builder's. */
    public static boolean sealed(ServerPlayer player) { return isLocked(player) && !building(player); }

    /** The draft dimension a builder is assigned to, whether or not it stands there right now. */
    public Optional<ResourceKey<net.minecraft.world.level.Level>> buildingLevel(ServerPlayer player) {
        var entry = players.get(player.getUUID());
        return entry != null && entry.player == player && entry.building ? Optional.of(entry.buildingLevel) : Optional.empty();
    }

    /** A builder inside its draft cannot go anywhere else; leaving clears the flag before the lifecycle moves it. */
    public static boolean rejectBuilderMove(ServerPlayer player, ServerLevel destination) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && entry.building
                && player.level().dimension().equals(entry.buildingLevel) && !destination.dimension().equals(entry.buildingLevel);
    }

    /**
     * Turns a verified lobby body into a builder in {@code draft} (design 11.2): the lobby body is kept aside exactly as
     * it is, the player arrives empty-handed in creative flight. False when the body may not build now.
     */
    public boolean startBuilding(ServerPlayer player, ServerLevel draft, Vec3 at) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (closed || recoveryPaused || !administrativeBody(this, entry, player) || !verifiedLobbyBody(player)
                || entry.observing || entry.adminSpectating || entry.building || entry.resume == null
                || !Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) return false;
        closeSuspendedContainer(player);
        player.stopUsingItem();
        player.stopRiding();
        player.ejectPassengers();
        // A fresh life enters the round with this very body, items picked up in camp included, so this (not the
        // older suspension) is what leaving must give back.
        var body = capture(player, false);
        entry.building = true;
        entry.buildingLevel = draft.dimension();
        entry.buildingBody = body;
        if (!player.teleportTo(draft, at.x, at.y, at.z, Set.of(), player.getYRot(), 0, true)) {
            entry.building = false;
            entry.buildingLevel = null;
            entry.buildingBody = null;
            return false;
        }
        clearPossessions(player);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.inventoryMenu.sendAllDataToRemote();
        return true;
    }

    /** Ends a builder's session: the lobby body it entered with comes back, in the lobby. False when not building. */
    public boolean stopBuilding(ServerPlayer player) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player || !entry.building) return false;
        endBuilding(entry);
        return true;
    }

    private void endBuilding(Entry entry) {
        var player = entry.player;
        var body = entry.buildingBody;
        entry.building = false;
        entry.buildingLevel = null;
        entry.buildingBody = null;
        // Tell the client first: once the body is loaded the server already believes it is in adventure mode.
        player.setGameMode(GameType.ADVENTURE);
        closeSuspendedContainer(player);
        player.stopUsingItem();
        player.stopRiding();
        player.ejectPassengers();
        // Nothing made in creative may outlive the session, whatever load() does with fields absent from the body.
        clearPossessions(player);
        player.getEnderChestInventory().clearContent();
        resetHumanAttributes(player);
        player.clearFire();
        player.clearFreeze();
        for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
        reloadSuspendedBody(entry, body);
        ((ProfileTagged) player).hardrush$generation(entry.target);
        ((ProfileTagged) player).hardrush$isolated(true);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
    }

    private static void clearPossessions(ServerPlayer player) {
        player.getInventory().clearContent();
        for (var slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        player.removeAllEffects();
    }

    /** Loads a sealed lobby body into the entity and stands it in the lobby. */
    private void reloadSuspendedBody(Entry entry, CompoundTag snapshot) {
        var player = entry.player;
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), snapshot.copy()));
        ((ProfileTagged) player).hardrush$clearLoadedProfile();
        restoreAdvancements(player, snapshot);
        moveToLobby(player);
        player.resetSentInfo();
        player.onUpdateAbilities();
        player.inventoryMenu.sendAllDataToRemote();
    }

    private static boolean administrativeBody(PlayerProfiles service, Entry entry, ServerPlayer player) {
        return service != null && !service.closed && entry != null && entry.player == player && entry.connected
                && entry.ready && entry.diskSafe && entry.problem.isEmpty() && !entry.admitted
                && (!dev.hardrush.participation.ParticipationControl.disabled(player.level().getServer(), player.getUUID()) || entry.exclusionInitialized)
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit && player.isAlive();
    }

    /** Commands or committed withdrawals grant observation; the target's OP status never implies a grant. */
    public static boolean isAdministrativeSpectator(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return administrativeBody(service, entry, player) && entry.adminSpectating && player.isSpectator()
                && dev.hardrush.round.RoundRuntime.administrativeSpectatingAllowed(player, player.level());
    }

    /** A camp or excluded lobby body attached to a round player's camera; it is never admitted. */
    public static boolean observing(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return administrativeBody(service, entry, player) && entry.observing && player.isSpectator();
    }

    /** Only a verified, standing lobby body may leave the camp as an observer. */
    public static boolean beginObservation(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (!administrativeBody(service, entry, player) || entry.adminSpectating || entry.observing
                || !verifiedLobbyBody(player)) return false;
        closeSuspendedContainer(player);
        player.stopUsingItem();
        entry.observing = true;
        player.setGameMode(GameType.SPECTATOR);
        return true;
    }

    /** Detach the camera and stand the body where it left the camp, or on {@code fallback} when that spot is gone. */
    public void endObservation(ServerPlayer player, Vec3 position, float yaw, dev.hardrush.core.lobby.plan.Landing fallback) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player || !entry.observing) return;
        entry.observing = false;
        player.setCamera(player);
        var camp = dev.hardrush.world.LobbyWorld.open(server);
        if (!camp.ready() || lobby.get() != camp.holdingLevel()) { moveToLobby(player); return; }
        if (!dev.hardrush.world.RoundWorldBundle.safeStanding(camp.holdingLevel(), net.minecraft.core.BlockPos.containing(position))) {
            if (camp.safeLanding(fallback)) placeInLobby(player, fallback); else moveToLobby(player);
            return;
        }
        if (!player.teleportTo(camp.holdingLevel(), position.x, position.y, position.z, Set.of(), yaw, 0, true))
            throw new IllegalStateException("观战结束返回营地的传送被拒绝");
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        applyLobbyMode(player);
    }

    public static boolean administrativeCommandAllowed(ServerPlayer player, String command) {
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())
                || dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer())) return false;
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (!administrativeBody(service, entry, player)
                || !dev.hardrush.round.RoundRuntime.administrativeSpectatingAllowed(player, player.level())) return false;
        var words = command.strip().split("\\s+", 3);
        return words[0].equals("tp") || words[0].equals("teleport")
                || words[0].equals("gamemode") && words.length > 1
                    && (words[1].equals("spectator") || words[1].equals("adventure"));
    }

    /** Preserve vanilla command permission checks and feedback while keeping admission sealed. */
    public static Optional<Boolean> administrativeGameMode(CommandSourceStack source, ServerPlayer player, GameType mode) {
        if (!HardRushProperties.competitionEnabled()) return Optional.empty();
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        // A builder's /gamemode is vanilla's; leaving restores the lobby mode.
        if (entry == null || entry.player != player || entry.building
                || mode != GameType.SPECTATOR && !entry.adminSpectating) return Optional.empty();
        if (!Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)
                || dev.hardrush.storage.FullCheckpointRuntime.saving(player.level().getServer())) {
            source.sendFailure(Component.literal("当前权限或保存状态不允许切换管理旁观。"));
            return Optional.of(false);
        }
        if (mode == GameType.SPECTATOR) {
            // Free flight is an operator's own tool; excluded players watch by riding a camera from the camp.
            if (entry.observing) {
                source.sendFailure(Component.literal("该玩家正在附身观战，潜行退出后才能切换。"));
                return Optional.of(false);
            }
            if (dev.hardrush.participation.ParticipationControl.disabled(player.level().getServer(), player.getUUID())
                    && !Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
                source.sendFailure(Component.literal("已退出本轮的玩家请在营地使用望远镜附身观战，不再授予自由旁观。"));
                return Optional.of(false);
            }
            if (!administrativeBody(service, entry, player)
                    || !dev.hardrush.round.RoundRuntime.administrativeSpectatingAllowed(player, player.level())) {
                source.sendFailure(Component.literal("管理旁观仅允许本轮未参赛且未准备，或已退出／取消资格的玩家；档案与轮次均须核验就绪。"));
                return Optional.of(false);
            }
            boolean previouslyGranted = entry.adminSpectating;
            closeSuspendedContainer(player);
            entry.adminSpectating = true;
            return Optional.of(player.setGameMode(GameType.SPECTATOR) || !previouslyGranted);
        }
        if (mode != GameType.ADVENTURE) {
            source.sendFailure(Component.literal("请用 /gamemode adventure <玩家> 结束管理旁观并返回大厅，再通过正常入口参赛。"));
            return Optional.of(false);
        }
        entry.adminSpectating = false;
        service.moveToLobby(player);
        return Optional.of(true);
    }

    public static boolean rejectAdministrativeSpectatorMove(ServerPlayer player, ServerLevel destination) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        // Always permit the lifecycle's forced return, including failed/rotating generations.
        return entry != null && entry.player == player && entry.adminSpectating && destination != service.lobby.get()
                && (!administrativeBody(service, entry, player)
                    || !dev.hardrush.round.RoundRuntime.administrativeSpectatingAllowed(player, destination));
    }

    /** Before durable suspension exists, retain vanilla player/advancement files rather than saving lobby coordinates. */
    public static boolean allowVanillaSave(ServerPlayer player) {
        if (!HardRushProperties.competitionEnabled()) return true;
        var service = SERVICES.get(player.level().getServer());
        if (service != null && service.recoveryPaused) return false;
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (entry == null || entry.player != player) return !((ProfileTagged) player).hardrush$isolated();
        // A celebration visitor or a builder body is temporary; its vanilla file keeps the sealed lobby state.
        if (entry.celebrationGuest || entry.building) return false;
        return entry.admitted || entry.diskSafe;
    }

    /**
     * True while this body's possessions are owned by its sealed suspension rather than the vanilla player file:
     * every managed body outside a round. Whatever it is later restored to (a reconnect, a re-entry, a monster lobby)
     * comes from that suspension, so the vanilla file of a lobby body never proves what it will keep.
     */
    public boolean keepsSuspended(ServerPlayer player) {
        var entry = players.get(player.getUUID());
        return entry != null && entry.player == player && !entry.admitted;
    }

    /**
     * Seals an item the camp has just put into {@code slot} of a verified lobby body (a history book) into that body's
     * suspension, so a reconnect or the next entry gives it back instead of the older sealed inventory. Completes true
     * once the suspension on disk holds it (also when it already did); false when the body cannot be sealed right now
     * or its sealed inventory has no free slot, leaving the caller free to retry without duplicating the item.
     */
    public CompletionStage<Boolean> sealLobbyDelivery(ServerPlayer player, int slot, ItemStack delivered) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (closed || recoveryPaused || delivered.isEmpty() || entry == null || entry.player != player || entry.admitted
                || entry.building || entry.celebrationGuest || !entry.ready || !entry.diskSafe || !entry.problem.isEmpty()
                || entry.resume == null || entry.target == null) return CompletableFuture.completedFuture(false);
        var base = entry.resume;
        var sealed = withInventoryItem(base, slot, delivered).orElse(null);
        if (sealed == null) return CompletableFuture.completedFuture(false);
        // entry.resume only ever holds what the suspension on disk holds, so an item already in it is durable.
        if (sealed == base) return CompletableFuture.completedFuture(true);
        var target = entry.target;
        // Replace the in-memory suspension first: an entry admitted before the write lands must already carry the item.
        entry.resume = sealed;
        var result = new CompletableFuture<Boolean>();
        // Retryable: a failed book write must not mark the whole profile service as unsafe for checkpoints and shutdown.
        CompletableFuture.runAsync(() -> {
            try { journal.join().suspend(player.getUUID(), target, encode(sealed)); }
            catch (IOException error) { throw new CompletionException(error); }
        }, io).whenComplete((ignored, error) -> callbacks.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("Sealing a lobby delivery for {} failed", player.getUUID(), error);
                        if (entry.resume == sealed) entry.resume = base;
                    }
                    result.complete(error == null && current(entry, target));
                }));
        return result;
    }

    /**
     * {@code snapshot} with {@code item} added to its main inventory: itself when already there, empty when no slot is
     * free. Restoring the snapshot puts each crafting-table stack it holds into a free slot, so those slots stay free.
     */
    private Optional<CompoundTag> withInventoryItem(CompoundTag snapshot, int preferredSlot, ItemStack item) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), snapshot);
        var items = new java.util.ArrayList<ItemStackWithSlot>();
        input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC).forEach(items::add);
        if (items.stream().anyMatch(sealed -> ItemStack.matches(sealed.stack(), item))) return Optional.of(snapshot);
        var used = new java.util.HashSet<Integer>();
        items.forEach(sealed -> used.add(sealed.slot()));
        long reserved = input.listOrEmpty("hardrush_external_crafting", ItemStack.OPTIONAL_CODEC).stream().filter(stack -> !stack.isEmpty()).count();
        if (Inventory.INVENTORY_SIZE - used.size() <= reserved) return Optional.empty();
        // A fresh camp body is its sealed snapshot, so the same slot keeps a reloaded body identical to the live one.
        int slot = preferredSlot >= 0 && preferredSlot < Inventory.INVENTORY_SIZE && !used.contains(preferredSlot) ? preferredSlot
                : java.util.stream.IntStream.range(0, Inventory.INVENTORY_SIZE).filter(free -> !used.contains(free)).findFirst().orElse(-1);
        if (slot < 0) return Optional.empty();
        items.add(new ItemStackWithSlot(slot, item.copy()));
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
        var list = output.list("Inventory", ItemStackWithSlot.CODEC);
        items.forEach(list::add);
        var updated = snapshot.copy();
        updated.put("Inventory", output.buildResult().get("Inventory"));
        return Optional.of(updated);
    }

    /**
     * The history books {@code body} (may be null) holds or {@code sealed} (may be null) keeps in its main inventory,
     * each once. They belong to the person rather than to a body: a camp body rebuilt for a new role gets them back.
     */
    private java.util.List<ItemStack> historyBooks(ServerPlayer body, CompoundTag sealed) {
        var books = new java.util.ArrayList<ItemStack>();
        java.util.function.Consumer<ItemStack> keep = stack -> {
            if (dev.hardrush.book.WrittenBookPort.isHistoryBook(stack) && books.stream().noneMatch(book -> ItemStack.matches(book, stack)))
                books.add(stack.copy());
        };
        if (body != null) for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) keep.accept(body.getInventory().getItem(slot));
        if (sealed != null) TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), sealed)
                .listOrEmpty("Inventory", ItemStackWithSlot.CODEC).forEach(item -> keep.accept(item.stack()));
        return books;
    }

    /**
     * The history books a camp body rebuilt from {@code old} keeps. A human life took the camp's books into the round,
     * so only its body counts (a death drops them, as vanilla does); a sortie leaves them sealed in camp.
     */
    private java.util.List<ItemStack> campHistoryBooks(Entry old, ServerPlayer previous) {
        return historyBooks(previous, old.admitted && !old.sortie ? null : old.resume);
    }

    /** Puts each of {@code books} that {@code body} does not hold yet into a free slot of its main inventory. */
    private static void giveBackHistoryBooks(ServerPlayer body, java.util.List<ItemStack> books) {
        var inventory = body.getInventory();
        for (var book : books) {
            boolean held = false;
            for (int slot = 0; slot < inventory.getContainerSize() && !held; slot++) held = ItemStack.matches(inventory.getItem(slot), book);
            if (held) continue;
            int free = inventory.getFreeSlot();
            // The library still hands out a copy; a full camp body is not worth failing a role change over.
            if (free < 0) { LOGGER.warn("No free slot to give back a history book to {}", body.getUUID()); continue; }
            inventory.setItem(free, book.copy());
        }
    }

    /**
     * A human killed by a monster player keeps what it carried (docs/plan/悬赏分档与追猎强度.md §1): just before vanilla
     * drops the death loot, every inventory slot (armour and offhand included) and the experience are sealed for the
     * next human life of this round, and the body is emptied so nothing spills and no experience orb drops. False when
     * they could not be sealed durably: the death then drops as vanilla does, so nothing is ever lost twice.
     */
    public boolean keepBelongings(ServerPlayer player, UUID roundId, int life) {
        requireMainThread();
        if (closed || journal == null) return false;
        var inventory = player.getInventory();
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        var items = output.list("Inventory", ItemStackWithSlot.CODEC);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) items.add(new ItemStackWithSlot(slot, stack.copy()));
        }
        output.putInt("XpLevel", player.experienceLevel);
        output.putFloat("XpP", player.experienceProgress);
        output.putInt("XpTotal", player.totalExperience);
        try {
            // TODO: [Refactor] A synchronous journal write on the main thread, once per such death: vanilla drops the loot
            // in this same call, so the belongings must be durable before the body is emptied. Move it off-thread only
            // together with holding back vanilla's drop until the write completes.
            journal.join().keep(player.getUUID(), roundId, life, encode(output.buildResult()));
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Could not keep the belongings of {}'s life {}; they drop as usual", player.getUUID(), life, failure);
            return false;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) inventory.setItem(slot, ItemStack.EMPTY);
        player.experienceLevel = 0;
        player.experienceProgress = 0;
        player.totalExperience = 0;
        return true;
    }

    /**
     * Puts back what the previous human life of this round kept when a monster player killed it, each item in its own
     * slot where that is free. Returns whether there was any, and adds what found no room to {@code leftovers}.
     */
    private boolean restoreBelongings(ServerPlayer player, UUID roundId, int life, java.util.List<ItemStack> leftovers) {
        if (life < 2) return false;
        Optional<byte[]> kept;
        try { kept = journal.join().kept(player.getUUID(), roundId, life - 1); }
        catch (IOException | RuntimeException failure) {
            LOGGER.error("Could not read the kept belongings of {}'s life {}", player.getUUID(), life - 1, failure);
            return false;
        }
        if (kept.isEmpty()) return false;
        CompoundTag tag;
        try { tag = decode(kept.get()); }
        catch (IOException failure) {
            LOGGER.error("Kept belongings of {}'s life {} are damaged", player.getUUID(), life - 1, failure);
            return false;
        }
        var input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag);
        var inventory = player.getInventory();
        var misplaced = new java.util.ArrayList<ItemStack>();
        input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC).forEach(item -> {
            if (item.slot() >= 0 && item.slot() < inventory.getContainerSize() && inventory.getItem(item.slot()).isEmpty())
                inventory.setItem(item.slot(), item.stack());
            else misplaced.add(item.stack());
        });
        for (var stack : misplaced) if (!inventory.add(stack) || !stack.isEmpty()) leftovers.add(stack);
        player.experienceLevel = input.getIntOr("XpLevel", 0);
        player.experienceProgress = input.getFloatOr("XpP", 0);
        player.totalExperience = input.getIntOr("XpTotal", 0);
        return true;
    }

    public static boolean isRespawnAllowed(ServerPlayer player) {
        return HardRushProperties.competitionEnabled() && ((ProfileTagged) player).hardrush$respawnPermitted()
                && isLocked(player);
    }

    public void onLogin(ServerPlayer player) { suspend(player, true); }

    /**
     * Quarantines the player's current body in the lobby. {@code fromLogin} distinguishes a real
     * (re)connection from an online player being re-suspended by a round change or respawn.
     */
    private void suspend(ServerPlayer player, boolean fromLogin) {
        requireMainThread();
        var tagged = (ProfileTagged) player;
        var source = tagged.hardrush$generation();
        boolean wasIsolated = tagged.hardrush$isolated();
        CompoundTag original = capture(player, false);
        if (player.connection == null) tagged.hardrush$loadedProfile().ifPresent(loaded -> {
            for (String lateField : new String[]{"Dimension", "Pos", "Rotation", "RootVehicle", ServerPlayer.ENDER_PEARLS_TAG}) {
                if (loaded.contains(lateField)) original.put(lateField, loaded.get(lateField).copy());
            }
        });
        // The carried/crafting stacks were captured above. Close before moving away from the old world;
        // native menu removal must not drop or return another copy while that suspension is retained.
        closeSuspendedContainer(player);
        var entry = new Entry(player, source, wasIsolated, original);
        entry.fromLogin = fromLogin;
        players.put(player.getUUID(), entry);
        tagged.hardrush$isolated(true);
        tagged.hardrush$respawnPermitted(false);
        player.stopRiding();
        player.ejectPassengers();
        if (player.connection == null) {
            // Before login registration there is no native entity membership to move.
            player.setServerLevel(lobby.get());
            player.setPos(lobbySpawn.get());
        } else {
            // A connected player still belongs to its old ServerLevel's entity/player lists.
            // Keep that source level until vanilla teleport unregisters it; changing the field
            // first turns the transfer into a same-level move and strands a player in the old world.
            moveToLobby(player);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.portalProcess = null;
        for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
    }

    public void onConnected(ServerPlayer player) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player) onLogin(player);
        ((ProfileTagged) player).hardrush$clearLoadedProfile();
        // Pre-connection capture cannot notify crafting listeners. The captured state remains
        // quarantined until this first safe opportunity to clear and close the native menu.
        closeSuspendedContainer(player);
        moveToLobby(player);
    }

    public void onDisconnect(ServerPlayer player) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry != null && entry.player == player) {
            entry.connected = false;
            players.remove(player.getUUID());
        }
    }

    /** Freeze gameplay immediately after vanilla has dropped the dead human's items. */
    public void holdDeath(ServerPlayer player) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player || !entry.admitted) return;
        entry.deathHeld = true;
        // The admitted native death remains the authoritative save until it is suspended.
        // Persisting isolated=true here would make prepare() prefer its older living archive.
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
    }

    /** Scoped override: a committed monster is born in the persistent lobby, never at a human bed/anchor;
     *  a celebration respawn lands exactly where the coordinator placed it. */
    public static Optional<net.minecraft.world.level.portal.TeleportTransition> scopedRespawnDestination(ServerPlayer player) {
        var celebration = CELEBRATION_RESPAWN.get();
        if (celebration != null) return Optional.of(celebration);
        var profiles = MONSTER_RESPAWN.get();
        if (profiles == null || profiles.server != player.level().getServer()) return Optional.empty();
        return Optional.of(new net.minecraft.world.level.portal.TeleportTransition(profiles.lobby.get(),
                profiles.lobbySpawn.get(), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
    }

    /** Only the durable monster identity may replace the death screen with a native lobby respawn. */
    public boolean enterMonsterLobby(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || round.rules().mode() != GameMode.HARDCORE || participant == null
                || participant.role() != ParticipantRole.MONSTER_LOBBY || entry == null || entry.player != player
                || !isReady(player.getUUID(), round)) return false;
        if (entry.monsterLobbyInitialized) return true;
        try {
            // Same-round progress and ender storage survive; the authoritative dead role can never
            // restore an older living inventory even if its vanilla checkpoint predates the death.
            if (!entry.admitted && !entry.fresh && entry.resume != null) {
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), entry.resume.copy()));
                ((ProfileTagged) player).hardrush$clearLoadedProfile();
                restoreAdvancements(player, entry.resume);
            }
            clearMonsterLobbyState(player);
            if (player.isDeadOrDying()) {
                MONSTER_RESPAWN.set(this);
                try {
                    var replacement = server.getPlayerList().respawn(player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    // PlayerList creates the native replacement; vanilla's packet handler normally
                    // performs these caller-owned steps after respawn() returns.
                    replacement.connection.player = replacement;
                    replacement.connection.resetPosition();
                    ((PlayerRespawnConnectionAccessor) replacement.connection).hardrush$restartClientLoadTimerAfterRespawn();
                    onMonsterRespawn(player, replacement, round);
                } finally { MONSTER_RESPAWN.remove(); }
            } else onMonsterRespawn(player, player, round);
            return true;
        } catch (RuntimeException error) { fail(entry, error); moveToLobby(player); return false; }
    }

    /** Replace the competition body only after its excluded role is durable; no native death is created. */
    public void reconcileParticipationExit(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (entry == null || entry.player != player || participant == null || !participant.role().isExcluded()) return;
        if (entry.exclusionInitialized && entry.exclusionRole != participant.role()) {
            entry.exclusionRole = participant.role();
            entry.adminSpectating = false;
            entry.observing = false;
            player.setCamera(player);
            placeExcluded(player);
        }
        if (!entry.exclusionInitialized) {
            if (player.isDeadOrDying()) {
                MONSTER_RESPAWN.set(this);
                try {
                    var replacement = server.getPlayerList().respawn(player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    replacement.connection.player = replacement;
                    replacement.connection.resetPosition();
                    ((PlayerRespawnConnectionAccessor) replacement.connection).hardrush$restartClientLoadTimerAfterRespawn();
                    onExcludedRespawn(player, replacement, round);
                } finally { MONSTER_RESPAWN.remove(); }
            } else onExcludedRespawn(player, player, round);
        }
    }

    /** An excluded body stands among the camp's arrivals; its spyglass is projected there. */
    private void placeExcluded(ServerPlayer player) {
        var camp = dev.hardrush.world.LobbyWorld.open(server);
        if (camp.ready() && lobby.get() == camp.holdingLevel() && camp.safeLanding(dev.hardrush.core.lobby.plan.Landing.CENTRAL))
            placeInLobby(player, dev.hardrush.core.lobby.plan.Landing.CENTRAL);
        else moveToLobby(player);
    }

    public void onExcludedRespawn(ServerPlayer previous, ServerPlayer replacement, RoundSnapshot round) {
        requireMainThread();
        var old = players.get(previous.getUUID());
        if (old != null && old.player == replacement && old.exclusionInitialized) return;
        var p = round.participants().get(replacement.getUUID());
        if (old == null || p == null || !p.role().isExcluded() || !generation(round).equals(old.target))
            throw new IllegalStateException("退出身体缺少已提交的本轮身份");
        var sealed = old.admitted ? capture(previous, false)
                : old.resume != null ? old.resume.copy() : old.original.copy();
        var books = campHistoryBooks(old, previous);
        replacement.stopUsingItem();
        clearMonsterLobbyState(replacement);
        giveBackHistoryBooks(replacement, books);
        resetHumanAttributes(replacement);
        replacement.setAbsorptionAmount(0);
        ((PlayerProfileResetAccessor) replacement).hardrush$setFoodData(new FoodData());
        replacement.setCamera(replacement);
        replacement.setPermanentlyInvulnerable(false);
        replacement.setNoGravity(false);
        for (var pearl : Set.copyOf(replacement.getEnderPearls())) pearl.discard();
        ((ProfileTagged) replacement).hardrush$generation(old.target);
        ((ProfileTagged) replacement).hardrush$lifeSequence(p.lifeSequence());
        ((ProfileTagged) replacement).hardrush$isolated(true);
        ((ProfileTagged) replacement).hardrush$respawnPermitted(false);
        ((ProfileTagged) replacement).hardrush$suspensionId(UUID.randomUUID().toString());
        placeExcluded(replacement);
        var rebound = new Entry(replacement, Optional.of(old.target), false, capture(replacement, false));
        rebound.target = old.target;
        rebound.exclusionInitialized = true;
        rebound.exclusionRole = p.role();
        players.put(replacement.getUUID(), rebound);
        var snapshot = rebound.original.copy();
        supply(() -> {
            var store = journal.join();
            store.sealParticipationExit(replacement.getUUID(), rebound.target, encode(sealed));
            store.suspend(replacement.getUUID(), rebound.target, encode(snapshot));
            return null;
        }).whenComplete((ignored, error) -> callbacks.execute(() -> {
            if (!current(rebound, generation(round))) return;
            if (error != null) { fail(rebound, error); return; }
            rebound.resume = snapshot;
            rebound.ready = true;
            rebound.diskSafe = true;
        }));
        replacement.resetSentInfo();
        replacement.inventoryMenu.sendAllDataToRemote();
    }

    /** A committed sortie opens only this monster body; never restore a human inventory. */
    public boolean deployMonster(ServerPlayer player, RoundSnapshot round, ServerLevel level, Vec3 position) {
        return deployMonster(player, round, level, position, 0);
    }
    /** The body lands facing {@code yaw}, normally toward its quarry. */
    public boolean deployMonster(ServerPlayer player, RoundSnapshot round, ServerLevel level, Vec3 position, float yaw) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || entry == null || entry.player != player || !entry.monsterLobbyInitialized
                || !isReady(player.getUUID(), round) || round.phase() != RoundPhase.RUNNING
                || participant == null || participant.role() != ParticipantRole.MONSTER_DEPLOYED
                || !dev.hardrush.world.RoundWorldBundle.owns(level)) return false;
        if (entry.admitted && player.level() == level) return true;
        // A hunt launched while watching the quarry lands straight from the camera.
        entry.observing = false;
        player.setCamera(player);
        closeSuspendedContainer(player);
        clearMonsterLobbyState(player);
        // A resumed sortie keeps the health the camp body has healed back to.
        if (!participant.wallet().hasParkedBody()) player.setHealth(player.getMaxHealth());
        if (!player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, 0, true)) return false;
        player.setGameMode(GameType.SURVIVAL);
        ((ProfileTagged) player).hardrush$isolated(false);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
        entry.admitted = true;
        entry.sortie = true;
        entry.deathHeld = false;
        player.resetSentInfo();
        player.onUpdateAbilities();
        return true;
    }

    /** Reapply a committed return before exposing any lobby actions. */
    public void returnMonsterToLobby(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player) return;
        entry.monsterLobbyInitialized = false;
        if (!enterMonsterLobby(player, round)) throw new IllegalStateException("已提交怪物无法安全返回大厅");
        // A paused sortie brings its wounds to camp; the camp's native peaceful regeneration heals them.
        var participant = round.participants().get(player.getUUID());
        if (participant != null && participant.role() == ParticipantRole.MONSTER_LOBBY)
            dev.hardrush.monster.MonsterParking.health(participant.wallet().parked())
                    .ifPresent(health -> player.setHealth(Math.max(1, Math.min(health, player.getMaxHealth()))));
    }

    public void onMonsterRespawn(ServerPlayer previous, ServerPlayer replacement, RoundSnapshot round) {
        requireMainThread();
        var old = players.get(previous.getUUID());
        if (old != null && old.player == replacement && old.monsterLobbyInitialized) return;
        var participant = round.participants().get(replacement.getUUID());
        if (round.rules().mode() != GameMode.HARDCORE || participant == null
                || participant.role() != ParticipantRole.MONSTER_LOBBY || old == null
                || !generation(round).equals(old.target)) throw new IllegalStateException("怪物大厅缺少已提交死亡身份");
        var books = campHistoryBooks(old, previous);
        clearMonsterLobbyState(replacement);
        giveBackHistoryBooks(replacement, books);
        replacement.setHealth(replacement.getMaxHealth());
        ((ProfileTagged) replacement).hardrush$generation(old.target);
        ((ProfileTagged) replacement).hardrush$lifeSequence(participant.lifeSequence());
        ((ProfileTagged) replacement).hardrush$isolated(true);
        ((ProfileTagged) replacement).hardrush$respawnPermitted(false);
        ((ProfileTagged) replacement).hardrush$suspensionId(UUID.randomUUID().toString());
        var camp = dev.hardrush.world.LobbyWorld.open(server);
        if (camp.ready() && lobby.get() == camp.holdingLevel()) placeInLobby(replacement, dev.hardrush.core.lobby.plan.Landing.MONSTER);
        else moveToLobby(replacement);
        var rebound = new Entry(replacement, Optional.of(old.target), false, capture(replacement, false));
        rebound.target = old.target;
        rebound.monsterLobbyInitialized = true;
        players.put(replacement.getUUID(), rebound);
        var snapshot = rebound.original.copy();
        supply(() -> { journal.join().suspend(replacement.getUUID(), rebound.target, encode(snapshot)); return null; })
                .whenComplete((ignored, error) -> callbacks.execute(() -> {
                    if (!current(rebound, generation(round))) return;
                    if (error != null) { fail(rebound, error); return; }
                    rebound.resume = snapshot;
                    rebound.ready = true;
                    rebound.diskSafe = true;
                }));
        replacement.resetSentInfo();
        replacement.inventoryMenu.sendAllDataToRemote();
    }

    private static void clearMonsterLobbyState(ServerPlayer player) {
        closeSuspendedContainer(player);
        player.getInventory().clearContent();
        for (var slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        player.experienceLevel = 0;
        player.totalExperience = 0;
        player.experienceProgress = 0;
        player.removeAllEffects();
        player.clearFire();
        player.clearFreeze();
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.stopRiding();
        player.ejectPassengers();
        player.portalProcess = null;
    }

    /**
     * A consumed entitlement starts an empty human life with ender storage kept, holding only the revival supplies
     * bought from the witch. In a bounty round its native
     * advancements start over too, so each life's threat counts only what that life achieved.
     */
    public boolean revive(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn) {
        return revive(player, round, () -> new dev.hardrush.world.NativeScatter.Arrival(spawnLevel, spawn, 0));
    }

    /**
     * {@code arrival} is resolved only after the committed identity has been checked. The body is either the camp body
     * the revival was committed from, or, after a disconnect or restart in between, the reconnected one whose profile
     * still holds that camp body ({@link #revivalBodyPending}).
     */
    public boolean revive(ServerPlayer player, RoundSnapshot round, Supplier<dev.hardrush.world.NativeScatter.Arrival> arrival) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || entry == null || entry.player != player
                || !entry.monsterLobbyInitialized && !revivalBodyPending(player, round)
                || !isReady(player.getUUID(), round) || participant == null
                || participant.role() != ParticipantRole.HUMAN_ACTIVE || !humansMayEnter(round)) return false;
        var landing = arrival.get();
        var spawnLevel = landing.level();
        var spawn = landing.position();
        if (!dev.hardrush.world.RoundWorldBundle.owns(spawnLevel)
                || !dev.hardrush.world.RoundWorldBundle.safeStanding(spawnLevel, net.minecraft.core.BlockPos.containing(spawn))) return false;
        try {
            var books = historyBooks(player, entry.resume);
            clearMonsterLobbyState(player);
            giveBackHistoryBooks(player, books);
            resetHumanAttributes(player);
            // Revoked before the resume snapshot is captured, or a reconnect would restore the previous life's progress.
            if (dev.hardrush.core.round.Bounty.enabled(round.rules())) revokeLifeProgress(player);
            player.setAbsorptionAmount(0);
            ((PlayerProfileResetAccessor) player).hardrush$setFoodData(new FoodData());
            player.getAbilities().apply(new Abilities().pack());
            player.setPermanentlyInvulnerable(false);
            player.setNoGravity(false);
            player.setRespawnPosition(null, false);
            player.setCamera(player);
            player.stopUsingItem();
            player.setAirSupply(player.getMaxAirSupply());
            for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
            for (var cooldown : Set.copyOf(((PlayerCooldownAccessor) player.getCooldowns()).hardrush$cooldowns().keySet())) {
                player.getCooldowns().removeCooldown(cooldown);
            }
            // A life a monster player ended kept its belongings: they come back first, in their own slots.
            var leftovers = new java.util.ArrayList<ItemStack>();
            boolean restored = restoreBelongings(player, round.roundId(), participant.lifeSequence(), leftovers);
            // The witch's carried revival supplies are part of this body, so they enter the resume snapshot below with it.
            dev.hardrush.revival.RevivalSupplies.fill(player, participant, leftovers);
            ((ProfileTagged) player).hardrush$lifeSequence(participant.lifeSequence());
            ((ProfileTagged) player).hardrush$respawnPermitted(false);
            // The respawn packet of this crossing carries it: the vanilla recovery compass points at the last human death.
            player.setLastDeathLocation(dev.hardrush.revival.FallenCompass.claim(server, player.getUUID(), round.roundId(), participant.lifeSequence()));
            if (!player.teleportTo(spawnLevel, spawn.x, spawn.y, spawn.z, Set.of(), landing.yaw(), 0, true)) {
                applyLobbyMode(player);
                return false;
            }
            player.setGameMode(GameType.SURVIVAL);
            entry.monsterLobbyInitialized = false;
            entry.deathHeld = false;
            entry.awaitingRespawn = false;
            entry.admitted = false;
            entry.ready = false;
            entry.fresh = false;
            var snapshot = capture(player, false);
            supply(() -> {
                journal.join().suspend(player.getUUID(), entry.target, encode(snapshot));
                // The revived body now holds them durably; matching only the previous life, a stale file is harmless.
                if (restored) journal.join().forgetKept(player.getUUID(), round.roundId(), participant.lifeSequence() - 1);
                return null;
            })
                    .whenComplete((ignored, error) -> callbacks.execute(() -> {
                        if (!current(entry, generation(round))) return;
                        if (error != null) { fail(entry, error); moveToLobby(player); return; }
                        // What found no room in the revived body lands at its feet in the wild, never in camp, and only now
                        // that the kept belongings are forgotten: a failed write keeps them for a retry, which would
                        // otherwise hand these out a second time.
                        for (var stack : leftovers) player.spawnAtLocation(player.level(), stack);
                        entry.resume = snapshot;
                        entry.diskSafe = true;
                        entry.ready = true;
                        entry.admitted = true;
                        ((ProfileTagged) player).hardrush$isolated(false);
                        player.resetSentInfo();
                        player.onUpdateAbilities();
                        player.inventoryMenu.sendAllDataToRemote();
                    }));
            return true;
        } catch (RuntimeException error) { fail(entry, error); moveToLobby(player); return false; }
    }

    /**
     * An altar revival committed, but its body was never built: the player left (or the server stopped) between the
     * commit and {@link #revive}, so the profile still holds the camp body of the life the revival ended. Its new life
     * enters through {@link #revive} like the revival it is, never through {@link #admit}, which would refuse the
     * previous life's body. The round record and the profile journal are both durable, so this holds across a restart.
     */
    public boolean revivalBodyPending(ServerPlayer player, RoundSnapshot round) {
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        return !closed && entry != null && entry.player == player && !entry.admitted && !entry.fresh
                && !entry.awaitingRespawn && entry.resume != null && participant != null
                && isReady(player.getUUID(), round) && campBodyOfRevivedLife(entry.resume, round, participant);
    }

    /**
     * {@code body} is the camp body {@code participant}'s current life was revived from at the altar. Only a hardcore
     * revival turns a life into the next human one, and every human body stands in a round world, so the previous
     * life saved anywhere else is that camp body. It holds nothing of the round, only what {@link #revive} keeps.
     */
    private static boolean campBodyOfRevivedLife(CompoundTag body, RoundSnapshot round, Participant participant) {
        return round.rules().mode() == GameMode.HARDCORE && participant.role() == ParticipantRole.HUMAN_ACTIVE
                && participant.lifeSequence() > 1
                && body.getIntOr("hardrush_life_sequence", 0) == participant.lifeSequence() - 1
                && !inRoundWorld(body, round);
    }

    private static boolean inRoundWorld(CompoundTag body, RoundSnapshot round) {
        String dimension = body.getStringOr("Dimension", "");
        return java.util.List.of("overworld", "nether", "end").stream().anyMatch(name ->
                dimension.equals(dev.hardrush.world.RoundWorldPaths.identifier(round.roundId(), name)));
    }

    /**
     * Vanilla display advancements only: recipes stay unlocked, a data pack's own quests keep their progress and
     * never pay their rewards twice, and the round's progress record keeps its union.
     */
    private static void revokeLifeProgress(ServerPlayer player) {
        for (var holder : player.level().getServer().getAdvancements().getAllAdvancements()) {
            if (holder.value().display().isEmpty() || !holder.id().getNamespace().equals("minecraft")) continue;
            var completed = new java.util.ArrayList<String>();
            player.getAdvancements().getOrStartProgress(holder).getCompletedCriteria().forEach(completed::add);
            completed.forEach(criterion -> player.getAdvancements().revoke(holder, criterion));
        }
        player.getAdvancements().setSelectedTab(null);
        player.getAdvancements().flushDirty(player, true);
    }

    private static void resetHumanAttributes(ServerPlayer player) {
        var defaults = new AttributeMap(Player.createAttributes().build());
        for (var packed : player.getAttributes().pack()) {
            var instance = player.getAttributes().getInstance(packed.attribute());
            if (instance == null) continue;
            instance.removeModifiers();
            instance.setBaseValue(defaults.hasAttribute(packed.attribute())
                    ? defaults.getBaseValue(packed.attribute()) : packed.attribute().value().getDefaultValue());
        }
        player.setHealth(player.getMaxHealth());
    }

    /** Ordinary vanilla respawn preserves ender storage/advancements and the vanilla bed/anchor destination. */
    public void onRespawn(ServerPlayer previous, ServerPlayer replacement, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(previous.getUUID());
        if (round.rules().mode() != GameMode.NORMAL || entry == null || entry.player != previous || !entry.admitted && !entry.deathScreenPermit
                || !generation(round).equals(entry.target)) {
            suspend(replacement, false);
            moveToLobby(replacement);
            return;
        }
        ((ProfileTagged) replacement).hardrush$generation(entry.target);
        ((ProfileTagged) replacement).hardrush$suspensionId(((ProfileTagged) previous).hardrush$suspensionId());
        ((ProfileTagged) replacement).hardrush$isolated(false);
        var participant = round.participants().get(replacement.getUUID());
        if (participant == null) throw new IllegalStateException("普通重生缺少本轮参与身份");
        int nextLife = Math.addExact(participant.lifeSequence(), 1);
        ((ProfileTagged) replacement).hardrush$lifeSequence(nextLife);
        ((ProfileTagged) replacement).hardrush$respawnPermitted(false);
        var rebound = new Entry(replacement, Optional.of(entry.target), false, capture(replacement, false));
        rebound.target = entry.target;
        rebound.awaitingRespawn = true;
        rebound.expectedRespawnLife = nextLife;
        ((ProfileTagged) replacement).hardrush$isolated(true);
        players.put(replacement.getUUID(), rebound);
        prepare(rebound, entry.target);
    }

    /**
     * A committed human life may enter (or resume) during a rescue too: when every human went offline the
     * rescue waits for exactly this reconnect, whose online event then resumes the race.
     */
    private static boolean humansMayEnter(RoundSnapshot round) {
        return round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE;
    }

    /** Unlock the vanilla bed/anchor destination only after the replacement life has durably committed. */
    public boolean confirmRespawn(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || round.rules().mode() != GameMode.NORMAL || !humansMayEnter(round)
                || entry == null || entry.player != player || !isReady(player.getUUID(), round)
                || participant == null || participant.role() != ParticipantRole.HUMAN_ACTIVE
                || participant.lifeSequence() != entry.expectedRespawnLife) return false;
        if (!entry.awaitingRespawn) return entry.admitted;
        entry.awaitingRespawn = false;
        entry.admitted = true;
        ((ProfileTagged) player).hardrush$isolated(false);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
        return true;
    }

    /** Restore a committed dead human's same-round context, exposing only vanilla's respawn request. */
    public boolean permitRespawn(ServerPlayer player, RoundSnapshot round, Predicate<ServerLevel> belongsToRound) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || round.rules().mode() != GameMode.NORMAL || round.phase() != RoundPhase.RUNNING
                || !isReady(player.getUUID(), round) || entry.player != player
                || participant == null || participant.role() != ParticipantRole.HUMAN_RESPAWNING) return false;
        if (entry.admitted && entry.deathHeld) {
            entry.deathScreenPermit = true;
            ((ProfileTagged) player).hardrush$respawnPermitted(true);
            return true;
        }
        if (entry.fresh || entry.admitted) return false;
        if (entry.deathScreenPermit || entry.awaitingRespawn) return true;
        try {
            var snapshot = entry.resume;
            if (snapshot == null) throw new IllegalStateException("本轮死亡恢复档案缺失");
            int savedLife = snapshot.getIntOr("hardrush_life_sequence", 0);
            float health = snapshot.getFloatOr("Health", 0);
            int nextLife = Math.addExact(participant.lifeSequence(), 1);
            boolean deadLife = health <= 0 && savedLife == participant.lifeSequence();
            boolean newbornLife = health > 0 && savedLife == nextLife;
            if (!deadLife && !newbornLife) throw new IllegalStateException("原版存档与死亡生命序号不一致，保留档案等待核验");
            var dimension = snapshot.getString("Dimension").map(Identifier::parse)
                    .map(id -> ResourceKey.create(Registries.DIMENSION, id)).map(server::getLevel).orElse(null);
            if (dimension == null || !belongsToRound.test(dimension)) throw new IllegalStateException("死亡位置不属于本轮已就绪维度");
            player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), snapshot.copy()));
            ((ProfileTagged) player).hardrush$clearLoadedProfile();
            restoreCarriedAndCrafting(player, snapshot);
            restoreAdvancements(player, snapshot);
            var position = player.position();
            if (!player.teleportTo(dimension, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), true)) {
                throw new IllegalStateException("本轮死亡档案恢复传送被拒绝");
            }
            ((ProfileTagged) player).hardrush$generation(entry.target);
            ((ProfileTagged) player).hardrush$isolated(true);
            ((ProfileTagged) player).hardrush$lifeSequence(savedLife);
            ((ProfileTagged) player).hardrush$respawnPermitted(deadLife);
            entry.deathScreenPermit = deadLife;
            entry.awaitingRespawn = newbornLife;
            entry.expectedRespawnLife = nextLife;
            player.resetSentInfo();
            player.inventoryMenu.sendAllDataToRemote();
            player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
            return true;
        } catch (RuntimeException error) { fail(entry, error); moveToLobby(player); return false; }
    }

    /** A stamped vanilla newborn saved before SQLite completion needs the existing replacement life committed, not a second respawn. */
    public boolean respawnCommitPending(ServerPlayer player, RoundSnapshot round) {
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        return entry != null && entry.player == player && entry.awaitingRespawn && !entry.deathScreenPermit
                && isReady(player.getUUID(), round) && participant != null
                && participant.role() == ParticipantRole.HUMAN_RESPAWNING
                && entry.expectedRespawnLife == participant.lifeSequence() + 1;
    }

    public void tick(RoundSnapshot round) {
        requireMainThread();
        if (closed || recoveryPaused) return;
        var target = generation(round);
        for (var entry : java.util.List.copyOf(players.values())) {
            if (!entry.connected) continue;
            // Before anything reads the body: a generation change would otherwise capture the creative body as an archive.
            if (entry.building) {
                if (target.equals(entry.target) && building(entry.player)
                        && dev.hardrush.lobby.workshop.Workshop.leaveReason(entry.player).isEmpty()) continue;
                endBuilding(entry);
            }
            if (entry.target != null && !entry.target.equals(target)) {
                suspend(entry.player, false);
                entry = players.get(entry.player.getUUID());
                moveToLobby(entry.player);
            }
            if (entry.target == null && entry.problem.isEmpty()) prepare(entry, target);
            if (entry.adminSpectating) {
                if (isAdministrativeSpectator(entry.player)) continue;
                entry.adminSpectating = false;
                moveToLobby(entry.player);
            }
            if (entry.observing) {
                if (observing(entry.player)) continue;
                entry.observing = false;
                entry.player.setCamera(entry.player);
                moveToLobby(entry.player);
            }
            // A new human life may already be at its round spawn while its journal is saving.
            // Only a completed profile can be reconciled as a movable lobby body.
            if (entry.ready && !entry.admitted && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit
                    && entry.player.isAlive()) {
                if (entry.player.level() != lobby.get()) moveToLobby(entry.player);
                applyLobbyMode(entry.player);
            }
        }
    }

    public boolean isReady(UUID playerId) {
        var entry = players.get(playerId);
        return entry != null && entry.connected && entry.ready && entry.problem.isEmpty();
    }

    public boolean isReady(UUID playerId, RoundSnapshot round) {
        var entry = players.get(playerId);
        return isReady(playerId) && generation(round).equals(entry.target)
                && ((ProfileTagged) entry.player).hardrush$generation().map(entry.target::equals).orElse(false);
    }

    public boolean manages(ServerPlayer player) {
        var entry = players.get(player.getUUID());
        return entry != null && entry.player == player;
    }

    /** Every connected body's profile operation must finish before a coherent save can begin. */
    public boolean readyForFullCheckpoint(RoundSnapshot round) {
        // A builder's creative body must never be written into a checkpoint.
        return ioFailure == null && !closed && players.values().stream().noneMatch(entry -> entry.building)
                && server.getPlayerList().getPlayers().stream()
                .allMatch(player -> isReady(player.getUUID(), round) && !players.get(player.getUUID()).awaitingRespawn
                        && !players.get(player.getUUID()).deathHeld);
    }

    public void flushForFullCheckpoint() throws Exception {
        requireMainThread();
        supply(() -> null).get(10, TimeUnit.SECONDS);
        if (ioFailure != null) throw new IOException("档案后台写入失败，禁止发布完整保存点", ioFailure);
    }

    /** Invalidate callbacks before replacing journal/player files; no lobby body may overwrite the image. */
    public void pauseForOnlineRecovery() throws Exception {
        requireMainThread();
        if (!recoveryPaused) for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) returnToLobby(player);
        recoveryPaused = true;
        players.clear();
        supply(() -> null).get(15, TimeUnit.SECONDS);
    }

    public static void verifyCheckpointProfiles(Path data, RoundSnapshot round) throws Exception {
        var store = new ProfileJournal(data.resolve("hardrush/profiles"));
        var target = generation(round);
        for (var participant : round.participants().values()) {
            if (participant.lifeSequence() == 0) continue;
            var saved = store.recovery(participant.id(), target);
            var body = decode(saved.snapshot());
            requireSnapshotGeneration(body, target);
            if (saved.transition().phase() != ProfileJournal.Phase.VERIFIED) throw new IOException("保存点玩家档案未完成核验");
            // A revival whose body was not built yet is restored with the camp body; its reconnect builds the body.
            if (participant.role() == ParticipantRole.HUMAN_ACTIVE && !campBodyOfRevivedLife(body, round, participant)) {
                if (body.getIntOr("hardrush_life_sequence", 0) != participant.lifeSequence()) throw new IOException("保存点人类生命序号不一致");
                if (!inRoundWorld(body, round)) throw new IOException("保存点人类位置不属于本轮世界");
            }
        }
    }

    /** Rebind connected entities to sealed checkpoint bodies, without requiring a disconnect or preparation. */
    public void reloadOnlineCheckpoint(RoundSnapshot round) throws Exception {
        requireMainThread();
        if (!recoveryPaused) throw new IllegalStateException("档案恢复屏障未持有");
        players.clear();
        ioFailure = null;
        journal = supply(() -> new ProfileJournal(journalRoot));
        var store = journal.get(15, TimeUnit.SECONDS);
        var target = generation(round);
        for (var player : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
            var saved = store.suspended(player.getUUID(), target);
            var original = saved.isPresent() ? decode(saved.get()) : capture(player, false);
            if (saved.isPresent()) requireSnapshotGeneration(original, target);
            var entry = new Entry(player, saved.isPresent() ? Optional.of(target) : Optional.empty(), saved.isPresent(), original);
            players.put(player.getUUID(), entry);
            closeSuspendedContainer(player);
            var tagged = (ProfileTagged) player;
            tagged.hardrush$isolated(true);
            tagged.hardrush$respawnPermitted(false);
            tagged.hardrush$clearLoadedProfile();
            moveToLobby(player);
        }
        recoveryPaused = false;
    }

    public void resumeAfterRecoveryFailure(RoundSnapshot round) throws Exception {
        if (recoveryPaused) reloadOnlineCheckpoint(round);
    }

    /** Preserve cursor/crafting stacks and advancements that vanilla player.dat alone does not seal. */
    public Map<UUID, byte[]> captureFullCheckpointBodies(RoundSnapshot round) throws IOException {
        requireMainThread();
        var snapshots = new LinkedHashMap<UUID, byte[]>();
        for (var entry : players.values()) {
            if (!entry.connected || !entry.admitted) continue;
            var participant = round.participants().get(entry.player.getUUID());
            if (participant == null || participant.lifeSequence() != ((ProfileTagged) entry.player).hardrush$lifeSequence()) {
                throw new IOException("保存点玩家生命与数据库不一致，禁止发布");
            }
            var snapshot = capture(entry.player, false);
            snapshot.putString(SUSPENSION_ID, UUID.randomUUID().toString());
            snapshots.put(entry.player.getUUID(), encode(snapshot));
        }
        return snapshots;
    }

    public static boolean celebrationRespawnInProgress() { return CELEBRATION_RESPAWN.get() != null; }

    /** A real round body keeps everything it carries when gathered at the dragon pool. */
    public boolean realRoundBody(ServerPlayer player) {
        var entry = players.get(player.getUUID());
        return entry != null && entry.player == player && entry.admitted && !entry.deathHeld && !entry.celebrationGuest
                && player.isAlive() && !player.isSpectator() && dev.hardrush.world.RoundWorldBundle.owns(player.level());
    }

    /** Native menus return carried/crafting stacks to this body; only cross-world riding and pearls are dropped. */
    public void moveIntoCelebration(ServerPlayer player, ServerLevel end, Vec3 position, float yaw) {
        requireMainThread();
        player.closeContainer();
        player.stopRiding();
        player.ejectPassengers();
        player.stopFallFlying();
        for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
        placeInCelebration(player, end, position, yaw);
    }

    /** A lobby, dead or reconnecting visitor joins as a newborn survival body; its sealed suspension is never touched. */
    public ServerPlayer enterCelebrationAsGuest(ServerPlayer player, RoundSnapshot round, ServerLevel end, Vec3 position,
            float yaw, GameType mode) {
        requireMainThread();
        var entry = requireCelebrationEntry(player, round);
        if (player.isDeadOrDying()) return respawnInCelebration(player, round, end, position, yaw, mode);
        dev.hardrush.monster.MonsterRoles.suspend(player);
        clearMonsterLobbyState(player);
        player.getInventory().setSelectedSlot(0);
        resetHumanAttributes(player);
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0);
        ((PlayerProfileResetAccessor) player).hardrush$setFoodData(new FoodData());
        for (var cooldown : Set.copyOf(((PlayerCooldownAccessor) player.getCooldowns()).hardrush$cooldowns().keySet())) {
            player.getCooldowns().removeCooldown(cooldown);
        }
        player.getAbilities().apply(new Abilities().pack());
        player.setPermanentlyInvulnerable(false);
        player.setNoGravity(false);
        player.setCamera(player);
        player.setAirSupply(player.getMaxAirSupply());
        for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
        markCelebrationGuest(entry);
        player.setGameMode(mode);
        placeInCelebration(player, end, position, yaw);
        player.resetSentInfo();
        player.onUpdateAbilities();
        player.inventoryMenu.sendAllDataToRemote();
        return player;
    }

    /** Server-driven native respawn inside the celebration; no competitive life or death event is created. */
    public ServerPlayer respawnInCelebration(ServerPlayer player, RoundSnapshot round, ServerLevel level, Vec3 position,
            float yaw, GameType mode) {
        requireMainThread();
        var entry = requireCelebrationEntry(player, round);
        dev.hardrush.monster.MonsterRoles.suspend(player);
        var replacement = nativeRespawn(player, new net.minecraft.world.level.portal.TeleportTransition(level, position,
                Vec3.ZERO, yaw, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
        markCelebrationGuest(rebindCelebration(entry, player, replacement));
        replacement.setGameMode(mode);
        replacement.setDeltaMovement(Vec3.ZERO);
        replacement.resetFallDistance();
        replacement.portalProcess = null;
        replacement.resetSentInfo();
        replacement.onUpdateAbilities();
        replacement.inventoryMenu.sendAllDataToRemote();
        return replacement;
    }

    /** Real bodies are sealed for this generation; guests keep their existing lobby suspension. */
    public ServerPlayer finishCelebration(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (player.isDeadOrDying() && entry != null && entry.player == player) {
            dev.hardrush.monster.MonsterRoles.suspend(player);
            var replacement = nativeRespawn(player, new net.minecraft.world.level.portal.TeleportTransition(lobby.get(),
                    lobbySpawn.get(), Vec3.ZERO, 180, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            markCelebrationGuest(rebindCelebration(entry, player, replacement));
            player = replacement;
        }
        dev.hardrush.monster.MonsterRoles.suspend(player);
        player.stopFallFlying();
        returnToLobby(player);
        entry = players.get(player.getUUID());
        entry.deathHeld = false;
        entry.awaitingRespawn = false;
        entry.deathScreenPermit = false;
        clearMonsterLobbyState(player);
        moveToLobby(player);
        player.setKnownMovement(Vec3.ZERO);
        player.portalProcess = null;
        player.inventoryMenu.sendAllDataToRemote();
        return player;
    }

    public static boolean celebrationGuest(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && entry.celebrationGuest;
    }

    private Entry requireCelebrationEntry(ServerPlayer player, RoundSnapshot round) {
        var entry = players.get(player.getUUID());
        if (closed || entry == null || entry.player != player || !isReady(player.getUUID(), round)) {
            throw new IllegalStateException("庆祝档案尚未核验");
        }
        return entry;
    }

    private static void markCelebrationGuest(Entry entry) {
        entry.admitted = true;
        entry.celebrationGuest = true;
        entry.adminSpectating = false;
        entry.deathHeld = false;
        entry.awaitingRespawn = false;
        entry.deathScreenPermit = false;
        entry.observing = false;
    }

    private static void placeInCelebration(ServerPlayer player, ServerLevel level, Vec3 position, float yaw) {
        if (!player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, 0, true)) {
            throw new IllegalStateException("庆祝集结传送失败");
        }
        player.portalProcess = null;
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private ServerPlayer nativeRespawn(ServerPlayer player, net.minecraft.world.level.portal.TeleportTransition destination) {
        CELEBRATION_RESPAWN.set(destination);
        try {
            var replacement = server.getPlayerList().respawn(player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            replacement.connection.player = replacement;
            replacement.connection.resetPosition();
            ((PlayerRespawnConnectionAccessor) replacement.connection).hardrush$restartClientLoadTimerAfterRespawn();
            return replacement;
        } finally { CELEBRATION_RESPAWN.remove(); }
    }

    /** Native replacement bodies carry no profile tags; keep the verified generation so isReady stays true. */
    private Entry rebindCelebration(Entry old, ServerPlayer previous, ServerPlayer replacement) {
        var tags = (ProfileTagged) replacement;
        tags.hardrush$generation(old.target);
        tags.hardrush$lifeSequence(((ProfileTagged) previous).hardrush$lifeSequence());
        tags.hardrush$suspensionId(((ProfileTagged) previous).hardrush$suspensionId());
        tags.hardrush$isolated(true);
        tags.hardrush$respawnPermitted(false);
        var rebound = new Entry(replacement, old.source, true, old.original);
        rebound.target = old.target;
        rebound.resume = old.resume;
        rebound.ready = old.ready;
        rebound.diskSafe = old.diskSafe;
        rebound.fresh = old.fresh;
        rebound.exclusionInitialized = old.exclusionInitialized;
        rebound.exclusionRole = old.exclusionRole;
        rebound.monsterLobbyInitialized = old.monsterLobbyInitialized;
        players.put(replacement.getUUID(), rebound);
        return rebound;
    }

    public String diagnostic(UUID playerId) {
        var entry = players.get(playerId);
        if (entry == null) return "unmanaged";
        return "source=" + entry.source + ",target=" + entry.target + ",initiallyIsolated=" + entry.wasIsolated
                + ",fresh=" + entry.fresh + ",ready=" + entry.ready + ",admitted=" + entry.admitted
                + ",celebrationGuest=" + entry.celebrationGuest + ",building=" + entry.building
                + ",originalInventory=" + entry.original.getListOrEmpty("Inventory")
                + ",resumeInventory=" + (entry.resume == null ? "missing" : entry.resume.getListOrEmpty("Inventory"));
    }

    public Optional<String> failure(UUID playerId) {
        var entry = players.get(playerId);
        return entry == null || entry.problem.isEmpty() ? Optional.empty() : Optional.of(entry.problem);
    }

    /** Called only after the participant/online transition has committed. Returns false without unlocking on failure. */
    public boolean admit(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn,
            Predicate<ServerLevel> belongsToRound) {
        return admit(player, round, () -> new dev.hardrush.world.NativeScatter.Arrival(spawnLevel, spawn, 0), belongsToRound, false);
    }

    /** {@code arrival} is resolved only when a fresh life actually enters, never for a resumed one. */
    public boolean admit(ServerPlayer player, RoundSnapshot round, Supplier<dev.hardrush.world.NativeScatter.Arrival> arrival,
            Predicate<ServerLevel> belongsToRound) {
        return admit(player, round, arrival, belongsToRound, false);
    }

    /** Restore an already active same-round life to view results; no admission or timing event is created. */
    public boolean admitCelebration(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn,
            Predicate<ServerLevel> belongsToRound) {
        return admit(player, round, () -> new dev.hardrush.world.NativeScatter.Arrival(spawnLevel, spawn, 0), belongsToRound, true);
    }

    private boolean admit(ServerPlayer player, RoundSnapshot round, Supplier<dev.hardrush.world.NativeScatter.Arrival> arrival,
            Predicate<ServerLevel> belongsToRound, boolean celebration) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || !isReady(player.getUUID(), round)
                || (celebration ? round.phase() != RoundPhase.SETTLING && round.phase() != RoundPhase.CELEBRATING
                        : !humansMayEnter(round)) || participant == null
                || participant.role() != ParticipantRole.HUMAN_ACTIVE
                || entry.player != player || !generation(round).equals(entry.target)) return false;
        // A revival whose body was never built has no round body to gather at the pool; it stays in camp like a fresh one.
        if (celebration && (entry.fresh || entry.awaitingRespawn || revivalBodyPending(player, round))) return false;
        if (entry.admitted) return true;
        if (entry.awaitingRespawn) return confirmRespawn(player, round);
        try {
            if (!entry.fresh) {
                var snapshot = entry.resume;
                if (snapshot == null) throw new IllegalStateException("本轮恢复档案缺失");
                if (snapshot.getIntOr("hardrush_life_sequence", 0) != participant.lifeSequence()) {
                    throw new IllegalStateException("人类档案生命序号不匹配，禁止恢复旧生命物资");
                }
                var dimension = snapshot.getString("Dimension").map(Identifier::parse)
                        .map(id -> ResourceKey.create(Registries.DIMENSION, id))
                        .map(server::getLevel).orElse(null);
                if (dimension == null || !belongsToRound.test(dimension)) {
                    throw new IllegalStateException("本轮恢复位置不属于已就绪的三个维度");
                }
                player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), snapshot.copy()));
                ((ProfileTagged) player).hardrush$clearLoadedProfile();
                restoreCarriedAndCrafting(player, snapshot);
                restoreAdvancements(player, snapshot);
                var position = player.position();
                if (!player.teleportTo(dimension, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), true)) {
                    throw new IllegalStateException("本轮人类档案恢复传送被拒绝");
                }
            } else {
                var landing = arrival.get();
                if (!belongsToRound.test(landing.level()) || !dev.hardrush.world.RoundWorldBundle.safeStanding(landing.level(), net.minecraft.core.BlockPos.containing(landing.position())))
                    throw new IllegalStateException("本轮入场落点未就绪或不安全，保留档案并留厅核验");
                var spawn = landing.position();
                if (!player.teleportTo(landing.level(), spawn.x, spawn.y, spawn.z, Set.of(), landing.yaw(), 0, true)) {
                    throw new IllegalStateException("本轮新生命入场传送被拒绝");
                }
            }
            ((ProfileTagged) player).hardrush$generation(entry.target);
            ((ProfileTagged) player).hardrush$lifeSequence(participant.lifeSequence());
            ((ProfileTagged) player).hardrush$isolated(false);
            ((ProfileTagged) player).hardrush$respawnPermitted(false);
            entry.admitted = true;
            player.setGameMode(GameType.SURVIVAL);
            if (!entry.fresh) {
                var input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), entry.resume);
                player.loadAndSpawnParentVehicle(input);
                player.loadAndSpawnEnderPearls(input);
            }
            player.resetSentInfo();
            player.onUpdateAbilities();
            player.inventoryMenu.sendAllDataToRemote();
            server.getPlayerList().sendActivePlayerEffects(player);
            return true;
        } catch (RuntimeException error) { fail(entry, error); moveToLobby(player); return false; }
    }

    /** A completed participant remains suspended in the lobby until another durable entry permits restoration. */
    public void returnToLobby(ServerPlayer player) {
        // A deployed monster cannot act in the lobby; carry the body there as a player, never as a monster template.
        dev.hardrush.monster.MonsterRoles.suspend(player);
        if (recoveryPaused) { moveToLobby(player); return; }
        var entry = players.get(player.getUUID());
        if (entry != null && entry.player == player && entry.building) endBuilding(entry);
        if (entry != null && entry.player == player) {
            entry.adminSpectating = false;
            entry.observing = false;
            player.setCamera(player);
        }
        if (entry != null && entry.player == player && !entry.admitted) {
            if (player.level() != lobby.get()) moveToLobby(player);
            else applyLobbyMode(player);
            return;
        }
        suspend(player, false);
        var suspended = players.get(player.getUUID());
        // Session failure can stop tick(). Save the captured same-generation survival state immediately,
        // so a normal stop while quarantined cannot discard the player's latest inventory or coordinates.
        suspended.source.ifPresent(generation -> prepare(suspended, generation));
        moveToLobby(player);
    }

    private void prepare(Entry entry, ProfileJournal.Generation target) {
        entry.target = target;
        var playerId = entry.player.getUUID();
        supply(() -> {
            var store = journal.join();
            boolean same = entry.source.map(target::equals).orElse(false);
            var transition = store.transition(playerId);
            boolean recordedTarget = transition.isPresent() && transition.get().target().equals(target);
            // PREPARED/APPLIED from another login or JVM is not permission to reset again.
            // A complete target suspension proves what the old reset produced; without it the
            // old vanilla checkpoint and partially applied entity cannot be distinguished.
            if (recordedTarget && (!same || transition.get().phase() != ProfileJournal.Phase.VERIFIED)) {
                var recovery = store.recovery(playerId, target);
                var restored = decode(recovery.snapshot());
                requireSnapshotGeneration(restored, target);
                if (recovery.transition().phase() != ProfileJournal.Phase.VERIFIED && !restored.getBooleanOr(FRESH, false)) {
                    throw new IOException("未核验的档案转换缺少新轮空档案，保留隔离等待核验");
                }
                return new Plan(true, restored, recovery.transition());
            }
            // Within a verified generation, a native active checkpoint can be newer than its
            // last lobby suspension; an isolated checkpoint must use the sealed suspension.
            if (same && recordedTarget) {
                byte[] resume;
                var storedSuspension = store.suspended(playerId, target);
                var storedSnapshot = storedSuspension.isPresent() ? decode(storedSuspension.get()) : null;
                if (storedSnapshot != null) requireSnapshotGeneration(storedSnapshot, target);
                boolean newerSuspension = storedSnapshot != null && !storedSnapshot.getBooleanOr(FRESH, false)
                        && !storedSnapshot.getStringOr(SUSPENSION_ID, "").equals(entry.original.getStringOr(SUSPENSION_ID, ""));
                if (entry.wasIsolated || newerSuspension) resume = storedSuspension
                        .orElseThrow(() -> new IOException("隔离中的本轮恢复档案缺失"));
                else {
                    var snapshot = entry.original.copy();
                    snapshot.putString(SUSPENSION_ID, UUID.randomUUID().toString());
                    resume = encode(snapshot);
                    store.suspend(playerId, target, resume);
                }
                return new Plan(true, decode(resume), transition.get());
            }
            if (same) throw new IOException("玩家代数存在，但匹配的持久化转换记录缺失");
            if (entry.source.isPresent() && entry.source.get().sequence() >= target.sequence()) {
                throw new IOException("玩家档案代数倒退或轮次 UUID 不一致，保留隔离等待核验");
            }
            if (entry.wasIsolated && entry.source.isEmpty()) {
                throw new IOException("隔离中的玩家缺少有效档案代数，禁止重置不确定的旧档案");
            }
            byte[] oldSnapshot = entry.wasIsolated && entry.source.isPresent()
                    ? store.suspended(playerId, entry.source.get()).orElseThrow(() -> new IOException("隔离中的旧轮恢复档案缺失，禁止以大厅数据替代旧档案"))
                    : encode(entry.original);
            var prepared = store.prepare(playerId, entry.source, target, oldSnapshot);
            return new Plan(false, null, prepared);
        }).whenComplete((plan, error) -> callbacks.execute(() -> {
            if (!current(entry, target)) return;
            if (error != null) { fail(entry, error); return; }
            if (plan.same()) {
                try {
                    entry.resume = plan.snapshot();
                    entry.fresh = entry.resume.getBooleanOr(FRESH, false);
                    // Load the already sealed reset output. Re-running resetForNewRound would
                    // revoke advancements/recipes again and replace its suspension identity.
                    if (entry.fresh) reloadSuspendedBody(entry, entry.resume);
                    // A crash can leave the vanilla file older than a sealed camp delivery; the camp body still shows it.
                    else giveBackHistoryBooks(entry.player, historyBooks(null, entry.resume));
                    ((ProfileTagged) entry.player).hardrush$generation(target);
                    ((ProfileTagged) entry.player).hardrush$suspensionId(entry.resume.getStringOr(SUSPENSION_ID, ""));
                    ((ProfileTagged) entry.player).hardrush$isolated(true);
                    ((ProfileTagged) entry.player).hardrush$respawnPermitted(false);
                    closeSuspendedContainer(entry.player);
                    if (plan.transition().phase() == ProfileJournal.Phase.VERIFIED) {
                        entry.diskSafe = true;
                        entry.ready = true;
                    } else finishTransition(entry, target, plan.transition());
                } catch (RuntimeException restoreError) { fail(entry, restoreError); moveToLobby(entry.player); }
                return;
            }
            try {
                resetForNewRound(entry.player, target);
                // Online players already saw the round end broadcast; only a real login missed it.
                if (entry.fromLogin && entry.source.isPresent() && !entry.source.get().equals(target)) {
                    dev.hardrush.notice.Notices.personal(entry.player, "你离线期间上一轮已结束，上一轮的物品已封存。现在是第 "
                            + dev.hardrush.notice.Notices.key(target.sequence()) + " 轮");
                }
                ((ProfileTagged) entry.player).hardrush$suspensionId(UUID.randomUUID().toString());
                entry.resume = capture(entry.player, true);
                entry.fresh = true;
                var resetSnapshot = entry.resume.copy();
                supply(() -> {
                    var store = journal.join();
                    store.suspend(playerId, target, encode(resetSnapshot));
                    if (plan.transition().phase() == ProfileJournal.Phase.PREPARED) {
                        store.advance(playerId, target, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
                    }
                    store.advance(playerId, target, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
                    return null;
                }).whenComplete((ignored, phaseError) -> callbacks.execute(() -> {
                    if (!current(entry, target)) return;
                    if (phaseError != null) fail(entry, phaseError);
                    else { entry.diskSafe = true; entry.ready = true; }
                }));
            } catch (RuntimeException resetError) { fail(entry, resetError); }
        }));
    }

    private void finishTransition(Entry entry, ProfileJournal.Generation target, ProfileJournal.Transition transition) {
        var playerId = entry.player.getUUID();
        supply(() -> {
            var store = journal.join();
            if (transition.phase() == ProfileJournal.Phase.PREPARED) {
                store.advance(playerId, target, ProfileJournal.Phase.PREPARED, ProfileJournal.Phase.APPLIED);
            }
            store.advance(playerId, target, ProfileJournal.Phase.APPLIED, ProfileJournal.Phase.VERIFIED);
            return null;
        }).whenComplete((ignored, error) -> callbacks.execute(() -> {
            if (!current(entry, target)) return;
            if (error != null) fail(entry, error);
            else { entry.diskSafe = true; entry.ready = true; }
        }));
    }

    private static void requireSnapshotGeneration(CompoundTag snapshot, ProfileJournal.Generation target) throws IOException {
        if (!snapshot.getStringOr("hardrush_round_id", "").equals(target.roundId().toString())
                || snapshot.getLongOr("hardrush_generation", 0) != target.sequence()
                || snapshot.getStringOr(SUSPENSION_ID, "").isBlank()) {
            throw new IOException("持久悬停档案的轮次／代数／悬停标记不匹配，保留隔离等待核验");
        }
    }

    private boolean current(Entry entry, ProfileJournal.Generation target) {
        return !closed && players.get(entry.player.getUUID()) == entry && target.equals(entry.target);
    }

    private void fail(Entry entry, Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        entry.problem = "档案转换失败，已保留旧档案并禁止参赛：" + error.getMessage();
        entry.ready = false;
        entry.admitted = false;
        ((ProfileTagged) entry.player).hardrush$isolated(true);
        ((ProfileTagged) entry.player).hardrush$respawnPermitted(false);
        LOGGER.error("{} UUID={}", entry.problem, entry.player.getUUID(), error);
        if (entry.connected && entry.player.connection != null) {
            dev.hardrush.notice.Notices.personal(entry.player, "你的存档读取出错，本轮暂时不能参赛，请联系管理员");
            dev.hardrush.notice.Notices.operators(entry.player.level().getServer(), entry.player.getScoreboardName() + "：" + entry.problem);
        }
    }

    /** Purpose-specific native relocation; ordinary lobby reconciliation never calls this every tick. */
    public void placeInLobby(ServerPlayer player, dev.hardrush.core.lobby.plan.Landing purpose) {
        requireMainThread();
        var camp = dev.hardrush.world.LobbyWorld.open(server);
        if (!camp.ready() || !camp.safeLanding(purpose)) throw new IllegalStateException("大厅落点未就绪：" + purpose);
        var spot = camp.scatteredLanding(purpose); var pos = spot.position();
        if (!player.teleportTo(camp.holdingLevel(), pos.x, pos.y, pos.z, Set.of(), spot.yaw(), 0, true))
            throw new IllegalStateException("大厅落点传送失败");
        player.setDeltaMovement(Vec3.ZERO); player.resetFallDistance(); applyLobbyMode(player);
    }

    private void moveToLobby(ServerPlayer player) {
        var position = lobbySpawn.get();
        if (!player.teleportTo(lobby.get(), position.x, position.y, position.z, Set.of(), 180, 0, true)) {
            throw new IllegalStateException("档案隔离到大厅的传送被拒绝");
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        applyLobbyMode(player);
    }

    private static void applyLobbyMode(ServerPlayer player) {
        if (player.connection == null) return;
        if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                && !player.getAbilities().flying && !player.getAbilities().mayfly
                && !player.getAbilities().mayBuild && !player.getAbilities().instabuild && !player.isNoGravity()) return;
        player.setGameMode(GameType.ADVENTURE);
        // Loading an old creative/spectator profile must not leave flying/no-gravity abilities in the lobby.
        player.getAbilities().flying = false;
        player.getAbilities().mayfly = false;
        player.getAbilities().mayBuild = false;
        player.getAbilities().instabuild = false;
        player.setNoGravity(false);
        player.onUpdateAbilities();
    }

    private static CompoundTag capture(ServerPlayer player, boolean fresh) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        output.store("hardrush_carried", ItemStack.OPTIONAL_CODEC, player.containerMenu.getCarried().copy());
        var craft = output.list("hardrush_crafting", ItemStack.OPTIONAL_CODEC);
        for (int slot = 0; slot < player.inventoryMenu.getCraftSlots().getContainerSize(); slot++) {
            craft.add(player.inventoryMenu.getCraftSlots().getItem(slot).copy());
        }
        if (player.containerMenu instanceof AbstractCraftingMenu external && external != player.inventoryMenu) {
            var externalItems = output.list("hardrush_external_crafting", ItemStack.OPTIONAL_CODEC);
            external.getInputGridSlots().forEach(slot -> externalItems.add(slot.getItem().copy()));
        }
        var advancements = output.child("hardrush_archived_advancements");
        for (var holder : player.level().getServer().getAdvancements().getAllAdvancements()) {
            var progress = player.getAdvancements().getOrStartProgress(holder);
            if (progress.hasProgress()) advancements.store(holder.id().toString(), AdvancementProgress.CODEC, progress);
        }
        var tag = output.buildResult();
        tag.putBoolean(FRESH, fresh);
        return tag.copy();
    }

    private static void restoreCarriedAndCrafting(ServerPlayer player, CompoundTag snapshot) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), snapshot);
        player.inventoryMenu.setCarried(input.read("hardrush_carried", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        var crafting = input.listOrEmpty("hardrush_crafting", ItemStack.OPTIONAL_CODEC);
        int slot = 0;
        for (var stack : crafting) {
            if (slot < player.inventoryMenu.getCraftSlots().getContainerSize()) player.inventoryMenu.getCraftSlots().setItem(slot++, stack);
        }
        // Crafting-table input belongs to this player. Shared chest contents are deliberately absent from the snapshot.
        for (var stack : input.listOrEmpty("hardrush_external_crafting", ItemStack.OPTIONAL_CODEC)) {
            if (stack.isEmpty()) continue;
            int empty = player.getInventory().getFreeSlot();
            if (empty < 0) throw new IllegalStateException("恢复工作台物品需要背包空位，旧档案已保留");
            player.getInventory().setItem(empty, stack);
        }
    }

    private static void restoreAdvancements(ServerPlayer player, CompoundTag snapshot) {
        var access = (PlayerAdvancementProfileAccessor) player.getAdvancements();
        player.getAdvancements().clearTriggers();
        access.hardrush$progress().clear();
        access.hardrush$visible().clear();
        access.hardrush$progressChanged().clear();
        access.hardrush$rootsToUpdate().clear();
        access.hardrush$firstPacket(true);
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), snapshot)
                .childOrEmpty("hardrush_archived_advancements");
        for (var holder : player.level().getServer().getAdvancements().getAllAdvancements()) {
            var progress = input.read(holder.id().toString(), AdvancementProgress.CODEC).orElseGet(AdvancementProgress::new);
            progress.update(holder.value().requirements());
            access.hardrush$progress().put(holder, progress);
            access.hardrush$progressChanged().add(holder);
            access.hardrush$markVisibility(holder);
            access.hardrush$registerListeners(holder);
        }
        player.getAdvancements().setSelectedTab(null);
        player.getAdvancements().flushDirty(player, true);
    }

    private static void closeSuspendedContainer(ServerPlayer player) {
        // InventoryMenu slot changes themselves send recipe packets, even if closeContainer is skipped.
        // Before a connection exists there is no open client menu; defer all menu mutations to onConnected.
        if (player.connection == null) return;
        player.containerMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu instanceof AbstractCraftingMenu crafting) {
            crafting.getInputGridSlots().forEach(slot -> slot.set(ItemStack.EMPTY));
        }
        player.inventoryMenu.getCraftSlots().clearContent();
        player.closeContainer();
    }

    /** Cross-round only. Ordinary death/respawn must never call this method. */
    public static void resetForNewRound(ServerPlayer player, ProfileJournal.Generation generation) {
        closeSuspendedContainer(player);
        player.getInventory().clearContent();
        for (var slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        player.getInventory().setSelectedSlot(0);
        player.getEnderChestInventory().clearContent();
        player.removeAllEffects();
        player.clearPostEffects();
        resetHumanAttributes(player);
        player.setAbsorptionAmount(0);
        ((PlayerProfileResetAccessor) player).hardrush$setFoodData(new FoodData());
        for (var cooldown : Set.copyOf(((PlayerCooldownAccessor) player.getCooldowns()).hardrush$cooldowns().keySet())) {
            player.getCooldowns().removeCooldown(cooldown);
        }
        player.experienceLevel = 0;
        player.totalExperience = 0;
        player.experienceProgress = 0;
        player.setScore(0);
        player.getAbilities().apply(new Abilities().pack());
        player.setGameMode(GameType.SURVIVAL);
        player.setPermanentlyInvulnerable(false);
        player.setNoGravity(false);
        player.setRespawnPosition(null, false);
        player.setLastDeathLocation(Optional.empty());
        player.stopRiding();
        player.ejectPassengers();
        player.setShoulderParrotLeft(Optional.empty());
        player.setShoulderParrotRight(Optional.empty());
        player.setCamera(player);
        player.stopUsingItem();
        player.stopSleeping();
        player.clearSleepingPos();
        player.setPose(Pose.STANDING);
        player.setSprinting(false);
        player.setSwimming(false);
        player.clearFire();
        player.clearFreeze();
        player.setAirSupply(player.getMaxAirSupply());
        player.resetFallDistance();
        player.resetCurrentImpulseContext();
        player.setDeltaMovement(Vec3.ZERO);
        player.setKnownMovement(Vec3.ZERO);
        player.portalProcess = null;
        player.setPortalCooldown(0);
        player.setInvulnerableTime(0);
        player.setArrowCount(0);
        player.setStingerCount(0);
        player.clearRaidOmenPosition();
        if (player.fishing != null) { player.fishing.discard(); player.fishing = null; }
        // Old pearls must never teleport a now-reset player back to the sealed generation.
        for (var pearl : Set.copyOf(player.getEnderPearls())) pearl.discard();
        for (var holder : player.level().getServer().getAdvancements().getAllAdvancements()) {
            var completed = new java.util.ArrayList<String>();
            player.getAdvancements().getOrStartProgress(holder).getCompletedCriteria().forEach(completed::add);
            completed.forEach(criterion -> player.getAdvancements().revoke(holder, criterion));
        }
        player.getAdvancements().setSelectedTab(null);
        player.resetRecipes(player.level().getServer().getRecipeManager().getRecipes());
        player.getRecipeBook().sendInitialRecipeBook(player);
        player.getAdvancements().flushDirty(player, true);
        ((ProfileTagged) player).hardrush$generation(generation);
        ((ProfileTagged) player).hardrush$lifeSequence(0);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
        ((ProfileTagged) player).hardrush$isolated(true);
        player.resetSentInfo();
        player.onUpdateAbilities();
        player.inventoryMenu.sendAllDataToRemote();
    }

    private static ProfileJournal.Generation generation(RoundSnapshot round) {
        return new ProfileJournal.Generation(round.roundId(), round.sequence());
    }

    private void requireMainThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Minecraft profile changes require the server thread");
    }

    private <T> CompletableFuture<T> supply(IoAction<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.run(); }
            catch (Exception error) { ioFailure = error; throw new CompletionException(error); }
        }, io);
    }

    private static byte[] encode(CompoundTag snapshot) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(snapshot, bytes);
        return bytes.toByteArray();
    }

    private static CompoundTag decode(byte[] bytes) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(16 * 1024 * 1024));
    }

    @Override public void close() throws Exception {
        requireMainThread();
        closed = true;
        io.shutdown();
        if (!io.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("Player archive worker did not drain; retain all files");
        journal.join();
        if (ioFailure != null) throw new IOException("玩家档案后台写入失败，禁止保存正常停服标记", ioFailure);
    }

    private static final class Entry {
        final ServerPlayer player;
        final Optional<ProfileJournal.Generation> source;
        final boolean wasIsolated;
        final CompoundTag original;
        /** Created by a real (re)connection rather than an in-session re-suspension. */
        boolean fromLogin;
        ProfileJournal.Generation target;
        CompoundTag resume;
        boolean connected = true;
        boolean diskSafe;
        boolean ready;
        boolean fresh;
        boolean admitted;
        /** Admitted as a deployed monster: the sortie body never carries the camp's history books. */
        boolean sortie;
        boolean adminSpectating;
        boolean exclusionInitialized;
        ParticipantRole exclusionRole;
        boolean observing;
        boolean awaitingRespawn;
        int expectedRespawnLife;
        boolean deathScreenPermit;
        boolean deathHeld;
        boolean monsterLobbyInitialized;
        boolean celebrationGuest;
        /** A builder in {@code buildingLevel}; {@code buildingBody} is the lobby body it entered with. */
        boolean building;
        ResourceKey<net.minecraft.world.level.Level> buildingLevel;
        CompoundTag buildingBody;
        String problem = "";
        Entry(ServerPlayer player, Optional<ProfileJournal.Generation> source, boolean wasIsolated, CompoundTag original) {
            this.player = player; this.source = source; this.wasIsolated = wasIsolated; this.original = original;
        }
    }
    private record Plan(boolean same, CompoundTag snapshot, ProfileJournal.Transition transition) {}
    @FunctionalInterface private interface IoAction<T> { T run() throws Exception; }
}
