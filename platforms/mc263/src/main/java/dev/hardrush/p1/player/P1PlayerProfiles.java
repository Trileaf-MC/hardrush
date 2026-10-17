package dev.hardrush.p1.player;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.p1.player.mixin.PlayerProfileResetAccessor;
import dev.hardrush.p1.player.mixin.PlayerCooldownAccessor;
import dev.hardrush.p1.player.mixin.PlayerAdvancementProfileAccessor;
import dev.hardrush.p1.player.mixin.PlayerRespawnConnectionAccessor;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Abilities;
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
public final class P1PlayerProfiles implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-p1-profiles");
    private static final Map<MinecraftServer, P1PlayerProfiles> SERVICES = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> UNMANAGED_FIXTURE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<P1PlayerProfiles> MONSTER_RESPAWN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CELEBRATION_RESPAWN = ThreadLocal.withInitial(() -> false);
    private static final String FRESH = "hardrush_fresh_profile";
    private static final String SUSPENSION_ID = "hardrush_suspension_id";
    private final MinecraftServer server;
    private final Supplier<ServerLevel> lobby;
    private final Supplier<Vec3> lobbySpawn;
    private final Map<UUID, Entry> players = new LinkedHashMap<>();
    private final ExecutorService io;
    private final CompletableFuture<ProfileJournal> journal;
    private boolean closed;
    private volatile Throwable ioFailure;
    private final java.util.concurrent.Executor callbacks;

    public P1PlayerProfiles(MinecraftServer server, Path root, Supplier<ServerLevel> lobby, Supplier<Vec3> lobbySpawn) {
        this(server, root, lobby, lobbySpawn, task -> server.execute(() -> {
            // A stopped vanilla event loop may execute submissions on the IO caller.
            // The durable journal is drained by close(); only the server thread may apply it.
            if (server.isSameThread()) task.run();
        }));
    }

    /** Callback executor seam lets a headless fixture hold callbacks while testing the independent IO barrier. */
    public P1PlayerProfiles(MinecraftServer server, Path root, Supplier<ServerLevel> lobby, Supplier<Vec3> lobbySpawn,
            java.util.concurrent.Executor callbacks) {
        this.server = server;
        this.lobby = lobby;
        this.lobbySpawn = lobbySpawn;
        this.callbacks = java.util.Objects.requireNonNull(callbacks);
        io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "hardrush-p1-profiles");
            thread.setDaemon(true);
            return thread;
        });
        journal = supply(() -> new ProfileJournal(root));
    }

    public static void install(MinecraftServer server, P1PlayerProfiles service) {
        if (SERVICES.putIfAbsent(server, service) != null) throw new IllegalStateException("Profile service already installed");
    }

    public static void uninstall(MinecraftServer server) { SERVICES.remove(server); }

    /** Explicit scope used by the separately packaged GameTest helper; no environment-based production exemption. */
    public static void runUnmanagedFixture(Runnable fixture) {
        int previous = UNMANAGED_FIXTURE_DEPTH.get();
        UNMANAGED_FIXTURE_DEPTH.set(previous + 1);
        try { fixture.run(); }
        finally {
            if (previous == 0) UNMANAGED_FIXTURE_DEPTH.remove();
            else UNMANAGED_FIXTURE_DEPTH.set(previous);
        }
    }

    /** HEAD of placeNewPlayer: before the login packet and before player/world tick or gameplay packets. */
    public static void beforeLogin(ServerPlayer player) {
        if (UNMANAGED_FIXTURE_DEPTH.get() > 0) {
            ((ProfileTagged) player).hardrush$isolated(false);
            return;
        }
        if (!Boolean.getBoolean("hardrush.p1.enabled")) return;
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
        if (UNMANAGED_FIXTURE_DEPTH.get() > 0) return;
        var service = SERVICES.get(player.level().getServer());
        if (service != null) service.onConnected(player);
    }

    public static boolean isLocked(ServerPlayer player) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) return false;
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (entry == null || entry.player != player) return ((ProfileTagged) player).hardrush$isolated();
        return !entry.admitted || entry.deathHeld;
    }

    public static boolean canMoveInLobby(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && !service.closed && !entry.admitted
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit
                && player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
                && dev.hardrush.p1.P1LobbyRuntime.lobbyActivityAllowed(player);
    }

    /** A verified, safe lobby body may read rules even while the competition is quarantined. */
    public static boolean verifiedLobbyBody(ServerPlayer player) {
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        return entry != null && entry.player == player && !service.closed && !entry.admitted
                && !entry.deathHeld && !entry.awaitingRespawn && !entry.deathScreenPermit
                && entry.ready && entry.problem.isEmpty() && entry.diskSafe && player.isAlive()
                && dev.hardrush.p1.world.P1LobbyWorld.isLobby(player.level());
    }

    public static boolean simulationFrozen(ServerPlayer player) {
        return isLocked(player) && !canMoveInLobby(player);
    }

    /** Before durable suspension exists, retain vanilla player/advancement files rather than saving lobby coordinates. */
    public static boolean allowVanillaSave(ServerPlayer player) {
        if (!Boolean.getBoolean("hardrush.p1.enabled")) return true;
        var service = SERVICES.get(player.level().getServer());
        var entry = service == null ? null : service.players.get(player.getUUID());
        if (entry == null || entry.player != player) return !((ProfileTagged) player).hardrush$isolated();
        return entry.admitted || entry.diskSafe;
    }

    public static boolean isRespawnAllowed(ServerPlayer player) {
        return Boolean.getBoolean("hardrush.p1.enabled") && ((ProfileTagged) player).hardrush$respawnPermitted()
                && isLocked(player);
    }

    public void onLogin(ServerPlayer player) {
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
        player.sendSystemMessage(Component.literal("玩家档案正在隔离并核验；完成后才允许加入本轮。"));
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

    /** Scoped override: a committed monster is born in the persistent lobby, never at a human bed/anchor. */
    public static Optional<net.minecraft.world.level.portal.TeleportTransition> monsterRespawnDestination(ServerPlayer player) {
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

    /** A committed sortie opens only this monster body; never restore a human inventory. */
    public boolean deployMonster(ServerPlayer player, RoundSnapshot round, ServerLevel level, Vec3 position) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || entry == null || entry.player != player || !entry.monsterLobbyInitialized
                || !isReady(player.getUUID(), round) || round.phase() != RoundPhase.RUNNING
                || participant == null || participant.role() != ParticipantRole.MONSTER_DEPLOYED
                || !dev.hardrush.p1.world.P1RoundWorldBundle.owns(level)) return false;
        if (entry.admitted && player.level() == level) return true;
        closeSuspendedContainer(player);
        clearMonsterLobbyState(player);
        player.setHealth(player.getMaxHealth());
        if (!player.teleportTo(level, position.x, position.y, position.z, Set.of(), 0, 0, true)) return false;
        player.setGameMode(GameType.SURVIVAL);
        ((ProfileTagged) player).hardrush$isolated(false);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
        entry.admitted = true;
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
    }

    public void onMonsterRespawn(ServerPlayer previous, ServerPlayer replacement, RoundSnapshot round) {
        requireMainThread();
        var old = players.get(previous.getUUID());
        if (old != null && old.player == replacement && old.monsterLobbyInitialized) return;
        var participant = round.participants().get(replacement.getUUID());
        if (round.rules().mode() != GameMode.HARDCORE || participant == null
                || participant.role() != ParticipantRole.MONSTER_LOBBY || old == null
                || !generation(round).equals(old.target)) throw new IllegalStateException("怪物大厅缺少已提交死亡身份");
        clearMonsterLobbyState(replacement);
        replacement.setHealth(replacement.getMaxHealth());
        ((ProfileTagged) replacement).hardrush$generation(old.target);
        ((ProfileTagged) replacement).hardrush$lifeSequence(participant.lifeSequence());
        ((ProfileTagged) replacement).hardrush$isolated(true);
        ((ProfileTagged) replacement).hardrush$respawnPermitted(false);
        ((ProfileTagged) replacement).hardrush$suspensionId(UUID.randomUUID().toString());
        moveToLobby(replacement);
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

    /** A consumed entitlement starts an empty human life while retaining this round's progress and ender storage. */
    public boolean revive(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || entry == null || entry.player != player || !entry.monsterLobbyInitialized
                || !isReady(player.getUUID(), round) || participant == null
                || participant.role() != ParticipantRole.HUMAN_ACTIVE || round.phase() != RoundPhase.RUNNING
                || !dev.hardrush.p1.world.P1RoundWorldBundle.owns(spawnLevel)
                || !dev.hardrush.p1.world.P1RoundWorldBundle.safeStanding(spawnLevel, net.minecraft.core.BlockPos.containing(spawn))) return false;
        try {
            clearMonsterLobbyState(player);
            resetHumanAttributes(player);
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
            ((ProfileTagged) player).hardrush$lifeSequence(participant.lifeSequence());
            ((ProfileTagged) player).hardrush$respawnPermitted(false);
            if (!player.teleportTo(spawnLevel, spawn.x, spawn.y, spawn.z, Set.of(), 0, 0, true)) {
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
            supply(() -> { journal.join().suspend(player.getUUID(), entry.target, encode(snapshot)); return null; })
                    .whenComplete((ignored, error) -> callbacks.execute(() -> {
                        if (!current(entry, generation(round))) return;
                        if (error != null) { fail(entry, error); moveToLobby(player); return; }
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
            onLogin(replacement);
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

    /** Unlock the vanilla bed/anchor destination only after the replacement life has durably committed. */
    public boolean confirmRespawn(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || round.rules().mode() != GameMode.NORMAL || round.phase() != RoundPhase.RUNNING
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
        if (closed) return;
        var target = generation(round);
        for (var entry : java.util.List.copyOf(players.values())) {
            if (!entry.connected) continue;
            if (entry.target != null && !entry.target.equals(target)) {
                onLogin(entry.player);
                entry = players.get(entry.player.getUUID());
                moveToLobby(entry.player);
            }
            if (entry.target == null && entry.problem.isEmpty()) prepare(entry, target);
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

    public static boolean celebrationRespawnInProgress() { return CELEBRATION_RESPAWN.get(); }

    public boolean savedInCelebrationEnd(ServerPlayer player, RoundSnapshot round, ServerLevel end) {
        var entry = players.get(player.getUUID());
        if (entry == null || entry.source.filter(generation(round)::equals).isEmpty()) return false;
        var saved = entry.resume != null ? entry.resume : entry.original;
        return saved.getStringOr("Dimension", "").equals(end.dimension().identifier().toString());
    }

    public CompletableFuture<Set<UUID>> celebrationRoster(RoundSnapshot round, Set<UUID> initial) {
        var tag = new CompoundTag();
        var ids = new ListTag();
        initial.stream().sorted().forEach(id -> ids.add(StringTag.valueOf(id.toString())));
        tag.put("players", ids);
        return supply(() -> {
            var saved = decode(journal.join().celebrationRoster(round.roundId(), encode(tag)));
            var result = new java.util.HashSet<UUID>();
            for (var id : saved.getListOrEmpty("players")) result.add(UUID.fromString(id.asString().orElseThrow()));
            return Set.copyOf(result);
        });
    }

    public CompletableFuture<Optional<CompoundTag>> celebrationState(UUID player, RoundSnapshot round) {
        return supply(() -> {
            var saved = journal.join().celebration(player, generation(round));
            if (saved.isEmpty()) return Optional.empty();
            var state = decode(saved.orElseThrow());
            requireSnapshotGeneration(state.getCompoundOrEmpty("original"), generation(round));
            if (state.contains("current")) requireSnapshotGeneration(state.getCompoundOrEmpty("current"), generation(round));
            return Optional.of(state);
        });
    }

    public CompletableFuture<Void> celebrationState(UUID player, RoundSnapshot round, CompoundTag state) {
        var copy = state.copy();
        return supply(() -> {
            requireSnapshotGeneration(copy.getCompoundOrEmpty("original"), generation(round));
            var store = journal.join();
            var previous = store.celebration(player, generation(round));
            if (previous.isPresent()) {
                var sealed = decode(previous.orElseThrow());
                if (!sealed.getCompoundOrEmpty("original").equals(copy.getCompoundOrEmpty("original"))) {
                    throw new IOException("庆祝不能覆盖已经封存的原物资");
                }
                if (sealed.getBooleanOr("returned", false) && !copy.getBooleanOr("returned", false)) {
                    throw new IOException("已返回大厅的庆祝状态不能回退");
                }
            }
            store.celebration(player, generation(round), encode(copy));
            return null;
        });
    }

    /** A quarantined login still owns the sealed competition position, not its temporary lobby coordinates. */
    public CompoundTag celebrationSnapshot(ServerPlayer player, boolean original) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var snapshot = original && entry != null && !entry.admitted && entry.resume != null
                ? entry.resume.copy() : capture(player, false);
        if (original && dev.hardrush.p0.monster.MonsterRoles.isMonster(player)) {
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
            for (var slot : EquipmentSlot.values()) output.store(slot.getName(), ItemStack.OPTIONAL_CODEC,
                    dev.hardrush.p0.monster.MonsterRoles.actualEquipment(player, slot));
            snapshot.put("hardrush_monster_equipment", output.buildResult());
        }
        return snapshot;
    }

    /** Applied while movement remains locked; the coordinator unlocks only after the kit checkpoint is durable. */
    public ServerPlayer prepareCelebrationBody(ServerPlayer player, RoundSnapshot round, CompoundTag snapshot,
            ServerLevel end, boolean freshKit) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        if (entry == null || !isReady(player.getUUID(), round)) throw new IllegalStateException("庆祝档案尚未核验");
        entry.deathHeld = true;
        dev.hardrush.p0.monster.MonsterRoles.suspendForCelebration(player);
        closeSuspendedContainer(player);
        boolean dead = player.isDeadOrDying();
        if (dead) {
            CELEBRATION_RESPAWN.set(true);
            MONSTER_RESPAWN.set(this);
            try {
                var replacement = server.getPlayerList().respawn(player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                replacement.connection.player = replacement;
                replacement.connection.resetPosition();
                ((PlayerRespawnConnectionAccessor) replacement.connection).hardrush$restartClientLoadTimerAfterRespawn();
                player = replacement;
            } finally { MONSTER_RESPAWN.remove(); CELEBRATION_RESPAWN.remove(); }
        }
        var target = generation(round);
        var rebound = new Entry(player, Optional.of(target), false, snapshot.copy());
        rebound.target = target;
        rebound.resume = snapshot.copy();
        rebound.ready = true;
        rebound.diskSafe = true;
        rebound.admitted = true;
        rebound.deathHeld = true;
        players.put(player.getUUID(), rebound);
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), snapshot.copy()));
        ((ProfileTagged) player).hardrush$clearLoadedProfile();
        restoreAdvancements(player, snapshot);
        closeSuspendedContainer(player);
        resetHumanAttributes(player);
        player.setHealth(player.getMaxHealth());
        player.deathTime = 0;
        ((PlayerProfileResetAccessor) player).hardrush$setFoodData(new FoodData());
        player.removeAllEffects();
        player.setGameMode(GameType.SURVIVAL);
        if (freshKit) dev.hardrush.p1.celebration.CelebrationKit.equip(player);
        else restoreCarriedAndCrafting(player, snapshot);
        ((ProfileTagged) player).hardrush$generation(target);
        ((ProfileTagged) player).hardrush$isolated(true);
        ((ProfileTagged) player).hardrush$respawnPermitted(false);
        Vec3 position = dead ? Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT).subtract(0, 1, 0) : player.position();
        if (!player.teleportTo(end, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), true)) {
            throw new IllegalStateException("庆祝入场传送失败");
        }
        player.portalProcess = null;
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.inventoryMenu.sendAllDataToRemote();
        return player;
    }

    public void unlockCelebration(ServerPlayer player, RoundSnapshot round) {
        var entry = players.get(player.getUUID());
        if (entry == null || entry.player != player || !isReady(player.getUUID(), round)) return;
        entry.deathHeld = false;
        ((ProfileTagged) player).hardrush$isolated(false);
    }

    public void freezeCelebration(ServerPlayer player) {
        var entry = players.get(player.getUUID());
        if (entry != null && entry.player == player) entry.deathHeld = true;
    }

    /** The immutable original lives in the celebration journal; never expose the temporary kit in the lobby. */
    public ServerPlayer finishCelebration(ServerPlayer player, RoundSnapshot round) {
        requireMainThread();
        if (player.isDeadOrDying()) {
            // Use a native replacement even for settled HUMAN_RESPAWNING, without a competitive life event.
            player = prepareCelebrationBody(player, round, celebrationSnapshot(player, true), lobby.get(), false);
        }
        dev.hardrush.p0.monster.MonsterRoles.suspendForCelebration(player);
        returnToLobby(player);
        var entry = players.get(player.getUUID());
        entry.deathHeld = false;
        entry.awaitingRespawn = false;
        entry.deathScreenPermit = false;
        clearMonsterLobbyState(player);
        moveToLobby(player);
        player.stopFallFlying();
        player.setKnownMovement(Vec3.ZERO);
        player.portalProcess = null;
        player.inventoryMenu.sendAllDataToRemote();
        return player;
    }

    public String diagnostic(UUID playerId) {
        var entry = players.get(playerId);
        if (entry == null) return "unmanaged";
        return "source=" + entry.source + ",target=" + entry.target + ",initiallyIsolated=" + entry.wasIsolated
                + ",fresh=" + entry.fresh + ",ready=" + entry.ready + ",admitted=" + entry.admitted
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
        return admit(player, round, spawnLevel, spawn, belongsToRound, false);
    }

    /** Restore an already active same-round life to view results; no admission or timing event is created. */
    public boolean admitCelebration(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn,
            Predicate<ServerLevel> belongsToRound) {
        return admit(player, round, spawnLevel, spawn, belongsToRound, true);
    }

    private boolean admit(ServerPlayer player, RoundSnapshot round, ServerLevel spawnLevel, Vec3 spawn,
            Predicate<ServerLevel> belongsToRound, boolean celebration) {
        requireMainThread();
        var entry = players.get(player.getUUID());
        var participant = round.participants().get(player.getUUID());
        if (closed || !isReady(player.getUUID(), round)
                || (celebration ? round.phase() != RoundPhase.SETTLING && round.phase() != RoundPhase.CELEBRATING
                        : round.phase() != RoundPhase.RUNNING) || participant == null
                || participant.role() != ParticipantRole.HUMAN_ACTIVE
                || entry.player != player || !generation(round).equals(entry.target)) return false;
        if (celebration && (entry.fresh || entry.awaitingRespawn)) return false;
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
                if (!belongsToRound.test(spawnLevel)) throw new IllegalStateException("本轮出生点维度未就绪");
                if (!player.teleportTo(spawnLevel, spawn.x, spawn.y, spawn.z, Set.of(), 0, 0, true)) {
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
        var entry = players.get(player.getUUID());
        if (entry != null && entry.player == player && !entry.admitted) {
            if (player.level() != lobby.get()) moveToLobby(player);
            else applyLobbyMode(player);
            return;
        }
        onLogin(player);
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
            if (entry.source.isPresent()) {
                var celebration = store.celebration(playerId, entry.source.orElseThrow());
                if (celebration.isPresent()) {
                    var original = decode(celebration.orElseThrow()).getCompoundOrEmpty("original");
                    requireSnapshotGeneration(original, entry.source.orElseThrow());
                    oldSnapshot = encode(original);
                }
            }
            var prepared = store.prepare(playerId, entry.source, target, oldSnapshot);
            return new Plan(false, null, prepared);
        }).whenComplete((plan, error) -> callbacks.execute(() -> {
            if (!current(entry, target)) return;
            if (error != null) { fail(entry, error); return; }
            if (plan.same()) {
                try {
                    entry.resume = plan.snapshot();
                    entry.fresh = entry.resume.getBooleanOr(FRESH, false);
                    if (entry.fresh) {
                        // Load the already sealed reset output. Re-running resetForNewRound would
                        // revoke advancements/recipes again and replace its suspension identity.
                        entry.player.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), entry.resume.copy()));
                        ((ProfileTagged) entry.player).hardrush$clearLoadedProfile();
                        restoreAdvancements(entry.player, entry.resume);
                        moveToLobby(entry.player);
                        entry.player.resetSentInfo();
                        entry.player.onUpdateAbilities();
                        entry.player.inventoryMenu.sendAllDataToRemote();
                    }
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
        if (entry.connected && entry.player.connection != null) entry.player.sendSystemMessage(Component.literal(entry.problem));
    }

    private void moveToLobby(ServerPlayer player) {
        var position = lobbySpawn.get();
        if (!player.teleportTo(lobby.get(), position.x, position.y, position.z, Set.of(), 0, 0, true)) {
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
        ProfileJournal.Generation target;
        CompoundTag resume;
        boolean connected = true;
        boolean diskSafe;
        boolean ready;
        boolean fresh;
        boolean admitted;
        boolean awaitingRespawn;
        int expectedRespawnLife;
        boolean deathScreenPermit;
        boolean deathHeld;
        boolean monsterLobbyInitialized;
        String problem = "";
        Entry(ServerPlayer player, Optional<ProfileJournal.Generation> source, boolean wasIsolated, CompoundTag original) {
            this.player = player; this.source = source; this.wasIsolated = wasIsolated; this.original = original;
        }
    }
    private record Plan(boolean same, CompoundTag snapshot, ProfileJournal.Transition transition) {}
    @FunctionalInterface private interface IoAction<T> { T run() throws Exception; }
}
