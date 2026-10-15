package dev.hardrush.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.round.*;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.book.P1BookPort;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.player.ProfileTagged;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p1.world.P1WorldPaths;
import dev.hardrush.persistence.SqliteRoundStore;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Test artifact only. A normal dedicated server, not a GameTestServer or graphical client. */
public final class P1ServerRestartRegression implements ModInitializer {
    private static final AtomicLong CLOCK = new AtomicLong();
    private static boolean installed;
    public static boolean clockInstalled() { return installed; }
    public static long observationTime() { return CLOCK.get(); }

    @Override public void onInitialize() {
        if (System.getProperty("hardrush.p1.restart.operation") == null) return;
        // Register the production listeners before this observer, independent of mod iteration order.
        P1LobbyRuntime.initialize();
        var current = new Fixture[1];
        ServerLifecycleEvents.SERVER_STARTED.register(server -> current[0] = new Fixture(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (current[0] != null) current[0].tick(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (current[0] != null) current[0].stopped(); });
    }

    private record Step(String name, BooleanSupplier work) {}
    private static final class Fixture {
        final MinecraftServer server;
        final RoundPhase target = RoundPhase.valueOf(System.getProperty("hardrush.p1.restart.phase"));
        final String operation = System.getProperty("hardrush.p1.restart.operation");
        final Path directory = Path.of(System.getProperty("hardrush.p1.restart.directory"));
        final Path root;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        final Actor a = new Actor("P1RestartA"), b = new Actor("P1RestartB");
        final JsonObject details = new JsonObject();
        final java.util.concurrent.atomic.AtomicBoolean escapedCallbacks = new java.util.concurrent.atomic.AtomicBoolean();
        final long started = System.nanoTime();
        long stepStarted = started;
        P1LobbySession session;
        P1PlayerProfiles profiles;
        P1RoundWorldBundle worlds;
        RoundSnapshot expected;
        UUID oldRound;
        UUID nativeDragonId;
        EnderDragonFight fight;
        CompletableFuture<?> query;
        Throwable failure;
        boolean stopping;
        BlockPos marker;

        Fixture(MinecraftServer server) {
            this.server = server;
            root = server.getWorldPath(LevelResource.ROOT);
            details.addProperty("operation", operation); details.addProperty("phase", target.name());
            details.addProperty("pid", ProcessHandle.current().pid());
            waitFor("runtime bootstrap", () -> P1LobbyRuntime.sessionState(server).filter(s -> s == P1LobbySession.State.OPEN).isPresent());
            act("install synchronized test clock", () -> {
                session = service("SESSIONS", P1LobbySession.class);
                profiles = service("PROFILES", P1PlayerProfiles.class);
                @SuppressWarnings("unchecked")
                var original = (java.util.function.BiConsumer<RoundSnapshot, RoundSnapshot>) get(session, "committedListener");
                session.onCommitted((before, after) -> {
                    if (!server.isSameThread()) { escapedCallbacks.set(true); throw new IllegalStateException("Committed runtime effects escaped the server thread"); }
                    original.accept(before, after);
                });
                var profileCallbacks = (java.util.concurrent.Executor) get(profiles, "callbacks");
                set(profiles, "callbacks", (java.util.concurrent.Executor) work -> profileCallbacks.execute(() -> {
                    if (!server.isSameThread()) { escapedCallbacks.set(true); throw new IllegalStateException("Profile effects escaped the server thread"); }
                    work.run();
                }));
                CLOCK.set(System.nanoTime()); installed = true;
                set(session, "clock", (MonotonicClock) CLOCK::get);
                set(session, "lastCheckpointNanos", CLOCK.get());
                Object engine = get(session, "engine"); set(engine, "boundary", CLOCK.get());
                if (operation.equals("recover")) {
                    try {
                        var data = JsonParser.parseString(Files.readString(directory.resolve("write-result.json"))).getAsJsonObject();
                        require(data.get("status").getAsString().equals("PASS"), "writer must pass");
                        require(data.get("pid").getAsLong() != ProcessHandle.current().pid(), "must use a distinct JVM");
                        expected = RoundSnapshotCodec.decode(Base64.getDecoder().decode(data.get("checkpoint").getAsString()));
                        marker = BlockPos.of(data.get("marker").getAsLong());
                        sameIdentity(expected, round());
                        require(round().phase() == expected.phase(), "saved phase restored before adapters");
                        require(round().challengeNanos() == expected.challengeNanos(), "downtime never added to challenge");
                        require(round().remainingPhaseNanos() == expected.remainingPhaseNanos(), "downtime never deducted from remaining phase");
                        require(round().participants().values().stream().noneMatch(Participant::online), "all restored identities initially offline");
                        require(expected.participants().keySet().equals(round().participants().keySet()), "no extra restored UUIDs");
                        expected.participants().forEach((id, participant) -> require(participant.withSession(participant.name(), false).equals(round().participants().get(id)), "durable identity/ready/life/personal time survives JVM exit"));
                        require(!round().timingUncertain(), "clean restore keeps trusted timing");
                        oldRound = expected.roundId();
                        if (data.has("nativeDragonId")) nativeDragonId = UUID.fromString(data.get("nativeDragonId").getAsString());
                        details.addProperty("recoveredRoundId", expected.roundId().toString());
                        details.addProperty("recoveredSequence", expected.sequence());
                    } catch (Exception error) { throw new IllegalStateException(error); }
                }
            });
            if (operation.equals("write")) writeScenario();
            else if (operation.equals("recover")) recoverScenario();
            else throw new IllegalArgumentException("Only write/recover allowed");
        }

        void writeScenario() {
            if (target == RoundPhase.PREPARING) {
                waitFor("partial native preparation", () -> {
                    worlds = service("WORLDS", P1RoundWorldBundle.class);
                    return worlds != null && worlds.state() == P1RoundWorldBundle.State.OPENING_DIMENSIONS
                            && server.getLevel(key(round().roundId(), "overworld")) != null;
                });
                act("preserve partial-world block", () -> {
                    marker = new BlockPos(2, 130, 2);
                    server.getLevel(key(round().roundId(), "overworld")).getChunk(marker);
                    server.getLevel(key(round().roundId(), "overworld")).setBlockAndUpdate(marker, Blocks.EMERALD_BLOCK.defaultBlockState());
                    stop();
                });
                return;
            }
            readyWorlds();
            act("write native world sentinels", this::writeWorlds);
            login(a); login(b);
            join(a); join(b);
            if (target == RoundPhase.WAITING) { act("stop with joined lobby identities", this::stop); return; }
            act("native ready command", () -> command(a, "hardrush ready"));
            waitFor("countdown committed", () -> round().phase() == RoundPhase.COUNTDOWN && idle());
            act("elapsed countdown", () -> advance(7));
            if (target == RoundPhase.COUNTDOWN) { act("stop countdown", this::stop); return; }
            waitFor("elapsed countdown checkpoint committed", this::idle);
            act("second human ready", () -> command(b, "hardrush ready"));
            waitFor("both ready identities committed", () -> idle() && round().participants().get(b.profile.id()).ready());
            act("finish default countdown", () -> advance(24));
            waitFor("native ordinary admission", () -> round().phase() == RoundPhase.RUNNING && idle()
                    && !P1PlayerProfiles.isLocked(a.player) && !P1PlayerProfiles.isLocked(b.player));
            act("ordinary native death", () -> a.player.hurtServer(a.player.level(), a.player.damageSources().genericKill(), Float.MAX_VALUE));
            waitFor("durable death", () -> idle() && round().participants().get(a.profile.id()).role() == ParticipantRole.HUMAN_RESPAWNING);
            act("native respawn packet", () -> {
                a.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                a.player = a.player.connection.player; acknowledge(a);
            });
            waitFor("second native life admitted", () -> idle() && round().participants().get(a.profile.id()).lifeSequence() == 2
                    && !P1PlayerProfiles.isLocked(a.player));
            act("write actual player state", () -> { state(a, 0); state(b, 1); advance(13); });
            waitFor("native progress persisted", () -> idle() && (int) get(session, "progressPending") == 0);
            if (target == RoundPhase.RUNNING) { act("stop active humans in separate native dimensions", this::stop); return; }
            act("native first dragon fight", () -> {
                move(a, worlds.level(Level.END), new Vec3(.5, 128, .5));
                // Use the exact SavedData object installed by the native ServerLevel constructor.
                // setDragonFight alone changes only the ticking field, not the persisted TYPE entry.
                fight = worlds.level(Level.END).getDragonFight();
                fight.skipArenaLoadedCheck();
                worlds.level(Level.END).getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(0, 0), 3);
            });
            waitFor("actual first dragon spawned", () -> fight.dragonUUID() != null && worlds.level(Level.END).getEntity(fight.dragonUUID()) instanceof EnderDragon);
            act("ordinary player lethal attack", () -> {
                var dragon = (EnderDragon) worlds.level(Level.END).getEntity(fight.dragonUUID());
                dragon.snapTo(.5, 68, .5, 0, 0); dragon.setOldPosAndRot(); dragon.setNoAi(true);
                dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING); dragon.setHealth(.1f);
                a.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD)); a.player.attack(dragon.head);
                require(session.competitionLocked(), "actual native lethal capture locks competition");
                // Stop while the real accepted terminal is still pending, before another server tick.
                if (target == RoundPhase.SETTLING) stop();
            });
            if (target == RoundPhase.SETTLING) return;
            waitFor("unique terminal committed", () -> round().result() != null && idle());
            act("first celebration checkpoint interval", () -> advance(1));
            waitFor("celebration committed", () -> round().phase() == RoundPhase.CELEBRATING && idle());
            if (target == RoundPhase.CELEBRATING) {
                act("elapsed celebration", () -> advance(119)); act("stop celebration", this::stop); return;
            }
            act("expire celebration", () -> advance(601));
            waitFor("rotation committed before next native release tick", () -> round().phase() == RoundPhase.ROTATING && idle());
            act("stop rotating", this::stop);
        }

        void recoverScenario() {
            // Logins begin before native worlds are verified and must remain in the permanent lobby.
            if (target == RoundPhase.ROTATING) { recoverRotation(); return; }
            if (target != RoundPhase.PREPARING && target != RoundPhase.COUNTDOWN) { login(a); login(b); }
            readyWorlds();
            act("check recovered real world contents", () -> {
                if (target == RoundPhase.PREPARING) {
                    worlds.level(Level.OVERWORLD).getChunk(marker);
                    require(worlds.level(Level.OVERWORLD).getBlockState(marker).is(Blocks.EMERALD_BLOCK), "partial preparation block survived new process");
                } else checkWorlds();
            });
            if (target == RoundPhase.PREPARING) { act("stop recovered preparation", this::stop); return; }
            if (target == RoundPhase.COUNTDOWN) {
                act("wait without any returning UUID", () -> advance(5));
                waitFor("recovered countdown remains suspended", this::idle);
                act("check saved countdown before any login", () -> {
                    require(round().phase() == RoundPhase.COUNTDOWN && round().remainingPhaseNanos() == expected.remainingPhaseNanos(), "READY without logged-in players preserves countdown remainder");
                });
                login(a); login(b);
            }
            if (target == RoundPhase.WAITING || target == RoundPhase.COUNTDOWN) {
                waitFor("lobby identity online commits", () -> idle() && round().participants().values().stream().allMatch(Participant::online));
                act("check lobby profiles", () -> {
                    require(round().phase() == target, "restored lobby/countdown phase preserved with returning ready UUID");
                    require(round().challengeNanos() == expected.challengeNanos(), "lobby does not add challenge time");
                    require(round().remainingPhaseNanos() == expected.remainingPhaseNanos(), "saved countdown remainder preserved");
                    require(P1PlayerProfiles.isLocked(a.player) && a.player.level() == server.overworld(), "lobby participant stays isolated");
                    stop();
                });
                return;
            }
            recoverActive();
        }

        void recoverRotation() {
            waitFor("inspect old native worlds before actual release", () -> {
                worlds = service("WORLDS", P1RoundWorldBundle.class);
                if (worlds == null || !worlds.ready()) return false;
                checkWorlds();
                return true;
            });
            // Production may begin closing immediately after adaptersReady; check old data before its next tick.
            waitFor("one next round", () -> round().sequence() == expected.sequence() + 1 && round().phase() == RoundPhase.WAITING && idle()
                    );
            login(a); login(b);
            act("verify actual old native release", () -> {
                require(worlds.state() == P1RoundWorldBundle.State.CLOSED, "old bundle completed native close");
                require(((Collection<?>) get(worlds, "closedNativeLevels")).size() == 3, "all three native IO closures completed");
                require(!round().roundId().equals(oldRound), "one fresh round UUID");
                for (String dimension : List.of("overworld", "nether", "end")) require(server.getLevel(key(oldRound, dimension)) == null, "old dimension unregistered");
                require(Files.exists(P1WorldPaths.manifest(root, oldRound)), "old manifest retained");
                require(round().participants().isEmpty(), "new round roster isolated");
                require(a.player.getEnderChestInventory().isEmpty() && a.player.totalExperience == 0 && a.player.getActiveEffects().isEmpty(), "new profile clears combat state");
            });
            waitFor("latest real history book", () -> a.player.getInventory().contains(stack -> {
                if (!P1BookPort.isHistoryBook(stack)) return false;
                var tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
                return tag.getStringOr("hardrush_book_round", "").equals(oldRound.toString())
                        && tag.getIntOr("hardrush_book_edition", 0) == 1 && tag.getIntOr("hardrush_book_volume", 0) == 1;
            }));
            act("query durable book claim", () -> query = session.ensureBook(a.profile.id(), oldRound, 1, 1));
            waitFor("inventory-save book ACK", () -> {
                if (!query.isDone()) return false;
                if (((dev.hardrush.core.record.BookClaim) query.join()).delivered()) return true;
                query = session.ensureBook(a.profile.id(), oldRound, 1, 1); return false;
            });
            act("check unique history", () -> query = session.history(10, 0).thenAccept(history -> require(history.size() == 1 && history.getFirst().roundId().equals(oldRound), "exactly one original terminal")));
            waitFor("history read", () -> query.isDone());
            act("finish actual restarted rotation", () -> { query.join(); stop(); });
        }

        void recoverActive() {
            waitFor("same-round profile admission", () -> idle() && !P1PlayerProfiles.isLocked(a.player) && !P1PlayerProfiles.isLocked(b.player));
            if (target != RoundPhase.RUNNING) {
                act("load original dying dragon chunk", () -> {
                    var end = worlds.level(Level.END);
                    end.getChunk(new BlockPos(0, 68, 0));
                    end.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(0, 0), 3);
                    var restored = end.getDragonFight();
                    LoggerFactory.getLogger("hardrush-p1-server-restart").info("Restored native dragon: expected={}, fight={}, killed={}, entity={}",
                            nativeDragonId, restored.dragonUUID(), restored.hasPreviouslyKilledDragon(), end.getEntity(nativeDragonId));
                });
                waitFor("original dying dragon entity loaded", () -> worlds.level(Level.END).getDragonFight().hasPreviouslyKilledDragon()
                        || worlds.level(Level.END).getEntity(nativeDragonId) instanceof EnderDragon);
            }
            act("check original human profiles", () -> {
                checkState(a, 0); checkState(b, 1);
                require(round().participants().get(a.profile.id()).lifeSequence() == 2, "ordinary second life persists");
                require(round().challengeNanos() == expected.challengeNanos(), "no stop/drain/downtime counted");
                if (target == RoundPhase.RUNNING) {
                    require(round().phase() == RoundPhase.RUNNING, "same live round resumes");
                    advance(5);
                } else {
                    require(round().result().equals(expected.result()) && round().result().winner().equals(a.profile.id()), "native winner preserved");
                    var restoredFight = worlds.level(Level.END).getDragonFight();
                    if (!restoredFight.hasPreviouslyKilledDragon()) {
                        var dragon = worlds.level(Level.END).getEntity(nativeDragonId);
                        require(nativeDragonId.equals(restoredFight.dragonUUID()) && dragon instanceof EnderDragon
                                && ((EnderDragon) dragon).getHealth() <= 0, "native dead first dragon identity and health persisted before animation completion");
                        restoredFight.skipArenaLoadedCheck();
                    }
                    if (target == RoundPhase.CELEBRATING) require(round().remainingPhaseNanos() == expected.remainingPhaseNanos(), "celebration resumes saved remainder");
                }
            });
            if (target != RoundPhase.RUNNING) waitFor("native death animation completes after restart", () -> worlds.level(Level.END).getDragonFight().hasPreviouslyKilledDragon());
            act("stop fully restored round", this::stop);
        }

        void readyWorlds() {
            waitFor("real three native dimensions ready", () -> {
                worlds = service("WORLDS", P1RoundWorldBundle.class);
                return worlds != null && worlds.ready() && idle();
            });
        }
        void writeWorlds() {
            marker = worlds.spawn().offset(3, 4, 3);
            int index = 0;
            for (var semantic : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var level = worlds.level(semantic);
                level.getChunk(marker);
                level.setBlockAndUpdate(marker, Blocks.EMERALD_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(marker.east(), Blocks.CHEST.defaultBlockState());
                ((ChestBlockEntity) level.getBlockEntity(marker.east())).setItem(0, new ItemStack(Items.AMETHYST_SHARD, ++index));
            }
            var overworld = worlds.level(Level.OVERWORLD);
            overworld.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            overworld.clockManager().setTotalTicks(overworld.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD), 17000);
            overworld.clockManager().setPaused(overworld.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD), true);
            overworld.getWeatherData().setRaining(true); overworld.getWeatherData().setRainTime(999999);
        }
        void checkWorlds() {
            int index = 0;
            for (var semantic : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var level = worlds.level(semantic);
                level.getChunk(marker);
                require(level.getSeed() == expected.seed() && server.getLevel(level.dimension()) == level, "native seed and registration restored");
                require(level.getBlockState(marker).is(Blocks.EMERALD_BLOCK), "saved construction survived process exit");
                var chest = (ChestBlockEntity) level.getBlockEntity(marker.east());
                require(chest != null && chest.getItem(0).is(Items.AMETHYST_SHARD) && chest.getItem(0).getCount() == ++index, "actual native chest contents survived");
                require(level.clockManager() != server.overworld().clockManager(), "round clock independent of permanent lobby");
            }
            require(worlds.level(Level.OVERWORLD).getOverworldClockTime() == 17000, "native SavedData clock restored");
            require(worlds.level(Level.OVERWORLD).getWeatherData().isRaining(), "native weather SavedData restored");
        }

        void login(Actor actor) {
            act("begin native login " + actor.profile.name(), () -> { actor.prepare = new PrepareSpawnTask(server, new NameAndId(actor.profile)); actor.prepare.start(packet -> {}); });
            waitFor("load native UUID " + actor.profile.name(), () -> actor.prepare.tick());
            act("register native player " + actor.profile.name(), () -> {
                actor.connection = new RecordingConnection(); actor.channel = new EmbeddedChannel(actor.connection);
                actor.player = actor.prepare.spawnPlayer(actor.connection, CommonListenerCookie.createInitial(actor.profile, false)); actor.prepare.close();
                acknowledge(actor);
                require(P1PlayerProfiles.isLocked(actor.player) && actor.player.level() == server.overworld(), "native login quarantined before world/profile verification");
            });
            waitFor("durable native profile " + actor.profile.name(), () -> profiles.isReady(actor.profile.id(), round()));
        }
        void join(Actor actor) {
            act("native join command", () -> command(actor, "hardrush join"));
            waitFor("join committed", () -> round().participants().containsKey(actor.profile.id()) && idle());
        }
        void state(Actor actor, int index) {
            actor.player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 7 + index));
            actor.player.getEnderChestInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 11 + index));
            actor.player.giveExperiencePoints(100 + index);
            actor.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 120000));
            actor.player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(worlds.level(Level.OVERWORLD).dimension(), worlds.spawn(), 0, 0), true), false);
            var progress = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            progress.value().criteria().keySet().forEach(key -> actor.player.getAdvancements().award(progress, key));
            move(actor, worlds.level(index == 0 ? Level.NETHER : Level.END), new Vec3(8.5 + index * 8, 160, 8.5));
            actor.player.setNoGravity(true);
        }
        void checkState(Actor actor, int index) {
            require(actor.player.getInventory().getItem(4).is(Items.DIAMOND) && actor.player.getInventory().getItem(4).getCount() == 7 + index, "native inventory preserved");
            require(actor.player.getEnderChestInventory().getItem(0).is(Items.GOLD_INGOT) && actor.player.getEnderChestInventory().getItem(0).getCount() == 11 + index, "native ender inventory preserved");
            require(actor.player.totalExperience == 100 + index && actor.player.hasEffect(MobEffects.NIGHT_VISION), "XP and effects preserved");
            require(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(worlds.level(Level.OVERWORLD).dimension(), worlds.spawn(), 0, 0), true).equals(actor.player.getRespawnConfig()), "exact native respawn binding preserved");
            var progress = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            require(actor.player.getAdvancements().getOrStartProgress(progress).isDone(), "native advancements preserved");
            var level = target != RoundPhase.RUNNING && index == 0 ? worlds.level(Level.END) : worlds.level(index == 0 ? Level.NETHER : Level.END);
            var position = target != RoundPhase.RUNNING && index == 0 ? new Vec3(.5, 128, .5) : new Vec3(8.5 + index * 8, 160, 8.5);
            require(actor.player.level() == level && actor.player.position().distanceTo(position) < .1, "saved native dimension and position restored");
            require(((ProfileTagged) actor.player).hardrush$generation().orElseThrow().roundId().equals(expected.roundId()), "same profile generation retained");
        }

        void stop() {
            details.addProperty("observedPhase", round().phase().name());
            details.addProperty("marker", marker.asLong());
            if (fight != null) details.addProperty("nativeDragonId", fight.dragonUUID().toString());
            stopping = true;
            // Same production halt path as the console stop command; do not pre-close any P1 service.
            server.halt(false);
        }
        void stopped() {
            try {
                if (failure != null) throw new IllegalStateException(failure);
                require(stopping && steps.isEmpty(), "all scenario steps completed before normal shutdown");
                require(!escapedCallbacks.get(), "all committed/profile effects remain on the server thread through stop");
                try (var store = new SqliteRoundStore(root.resolve("hardrush/p1.sqlite"))) {
                    var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                    require(saved.cleanShutdown() && !saved.timingUncertain(), "normal production stop must persist clean trusted checkpoint");
                    if (operation.equals("write")) {
                        require(saved.phase() == target, "writer stopped at requested durable phase: " + saved.phase());
                        if (target == RoundPhase.COUNTDOWN) require(saved.remainingPhaseNanos() == TimeUnit.SECONDS.toNanos(23), "writer saves 30 minus 7 countdown seconds");
                        if (target == RoundPhase.CELEBRATING) require(saved.remainingPhaseNanos() == TimeUnit.SECONDS.toNanos(480), "writer saves 600 minus 120 celebration seconds");
                        if (target == RoundPhase.RUNNING || saved.result() != null) {
                            require(saved.challengeNanos() == TimeUnit.SECONDS.toNanos(13), "writer saves known thirteen active seconds");
                            require(saved.participants().get(a.profile.id()).humanNanos() == TimeUnit.SECONDS.toNanos(13)
                                    && saved.participants().get(b.profile.id()).humanNanos() == TimeUnit.SECONDS.toNanos(13), "both human durations use actual admitted interval");
                        }
                    }
                    else if (target == RoundPhase.RUNNING) require(saved.challengeNanos() == expected.challengeNanos() + TimeUnit.SECONDS.toNanos(5), "resumed timer adds only five active test seconds");
                    if (target == RoundPhase.RUNNING || target == RoundPhase.SETTLING || target == RoundPhase.CELEBRATING || target == RoundPhase.ROTATING) {
                        UUID source = operation.equals("write") ? saved.roundId() : expected.roundId();
                        var records = store.participantRecords(source, 10, 0);
                        require(records.size() == 2, "exactly two actual admitted participant records");
                        for (var record : records) {
                            boolean first = record.playerId().equals(a.profile.id());
                            require(record.advancementCount() >= 1 && record.deathCount() == (first ? 1 : 0)
                                    && record.revivalCount() == (first ? 1 : 0), "native progress and ordinary death/respawn records persist");
                            if (!first || target != RoundPhase.RUNNING) require(record.firstEndNanos() != null, "native first End entry persists");
                        }
                        if (target != RoundPhase.RUNNING) {
                            var terminal = store.terminal(source).orElseThrow();
                            var original = RoundSnapshotCodec.decode(terminal.snapshot().payload());
                            var history = store.roundHistory(source).orElseThrow();
                            require(original.result().winner().equals(a.profile.id()) && original.challengeNanos() == TimeUnit.SECONDS.toNanos(13), "one original native trusted terminal persists");
                            require(history.result().equals(original.result()) && history.trustedWinner() && store.history(10, 0).size() == 1, "history matches exactly one original terminal");
                        }
                    }
                    details.addProperty("checkpoint", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(saved)));
                    details.addProperty("roundId", saved.roundId().toString()); details.addProperty("sequence", saved.sequence());
                    details.addProperty("challengeNanos", saved.challengeNanos()); details.addProperty("remainingPhaseNanos", saved.remainingPhaseNanos());
                }
                details.addProperty("status", "PASS");
            } catch (Throwable error) { details.addProperty("status", "FAIL"); details.addProperty("error", error.toString()); error.printStackTrace(); }
            try { Files.writeString(directory.resolve(operation + "-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(details) + "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
            LoggerFactory.getLogger("hardrush-p1-server-restart").info("{} {} {} pid={}", details.get("status").getAsString(), operation, target, ProcessHandle.current().pid());
        }
        void tick() {
            if (stopping) return;
            try {
                require(System.nanoTime() - started < TimeUnit.MINUTES.toNanos(5)
                        && System.nanoTime() - stepStarted < TimeUnit.MINUTES.toNanos(2), "scenario timeout at " + (steps.isEmpty() ? "complete" : steps.getFirst().name()));
                require(P1LobbyRuntime.sessionState(server).filter(s -> s == P1LobbySession.State.FAILED).isEmpty(), P1LobbyRuntime.status(server));
                acknowledge(a); acknowledge(b);
                var step = steps.peek();
                if (step != null && step.work().getAsBoolean()) { steps.remove(); stepStarted = System.nanoTime(); LoggerFactory.getLogger("hardrush-p1-server-restart").info("Step passed: {}", step.name()); }
            } catch (Throwable error) { failure = error; error.printStackTrace(); stopping = true; server.halt(false); }
        }
        boolean idle() { return session != null && !session.mutationPending(); }
        RoundSnapshot round() { return P1LobbyRuntime.snapshot(server).orElseThrow(); }
        void act(String name, Runnable work) { steps.add(new Step(name, () -> { work.run(); return true; })); }
        void waitFor(String name, BooleanSupplier work) { steps.add(new Step(name, work)); }
        void advance(long seconds) { CLOCK.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); }
        void command(Actor actor, String text) { try { server.getCommands().getDispatcher().execute(text, actor.player.createCommandSourceStack()); } catch (Exception error) { throw new IllegalStateException(error); } }
        void move(Actor actor, ServerLevel level, Vec3 pos) { actor.player.teleport(new TeleportTransition(level, pos, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING)); acknowledge(actor); }
        void acknowledge(Actor actor) {
            if (actor.player == null || actor.player.connection == null) return;
            actor.connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance).map(ClientboundPlayerPositionPacket.class::cast)
                    .reduce((old, packet) -> packet).ifPresent(packet -> actor.player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(packet.id(), actor.player.getX(), actor.player.getY(), actor.player.getZ(), actor.player.getYRot(), actor.player.getXRot())));
            actor.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket()); actor.connection.packets.clear();
        }
        <T> T service(String name, Class<T> type) { return type.cast(((Map<?, ?>) getStatic(P1LobbyRuntime.class, name)).get(server)); }
    }

    private static void sameIdentity(RoundSnapshot before, RoundSnapshot after) { require(before.roundId().equals(after.roundId()) && before.sequence() == after.sequence() && before.seed() == after.seed() && before.rules().equals(after.rules()), "round UUID/sequence/seed/rules remain exact"); }
    private static ResourceKey<Level> key(UUID round, String name) { return ResourceKey.create(Registries.DIMENSION, Identifier.parse(P1WorldPaths.identifier(round, name))); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Object getStatic(Class<?> type, String name) { try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static Object get(Object owner, String name) { try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void set(Object owner, String name, Object value) { try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static final class Actor {
        final GameProfile profile;
        ServerPlayer player; PrepareSpawnTask prepare; RecordingConnection connection; EmbeddedChannel channel;
        Actor(String name) { profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name); }
    }
    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) { if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record); else packets.add(packet); }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
