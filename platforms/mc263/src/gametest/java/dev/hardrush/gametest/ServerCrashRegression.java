package dev.hardrush.gametest;

import static dev.hardrush.gametest.ServerRestartRegression.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.persistence.SqliteRoundStore;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.sqlite.Function;

/** Test mod only: an external parent SIGKILLs this dedicated JVM at a real SQLite boundary. */
public final class ServerCrashRegression implements ModInitializer {
    @Override public void onInitialize() {
        if (System.getProperty("hardrush.p3.crash.scenario") == null) return;
        RoundRuntime.initialize();
        var fixture = new CrashFixture[1];
        ServerLifecycleEvents.SERVER_STARTED.register(server -> fixture[0] = new CrashFixture(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (fixture[0] != null) fixture[0].tick(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (fixture[0] != null) fixture[0].stopped(); });
    }

    private static final class CrashFixture extends Fixture {
        final String scenario = System.getProperty("hardrush.p3.crash.scenario");
        final String boundary = System.getProperty("hardrush.p3.crash.boundary");
        final Path database;
        final AtomicBoolean armed = new AtomicBoolean();
        RoundSnapshot before;
        Vec3 arena;
        CompletableFuture<?> ioTask;
        int recoveryTicks;
        long baselineWalBytes;

        CrashFixture(MinecraftServer server) {
            super(server, false);
            database = root.resolve("hardrush/p1.sqlite");
            require(Set.of("AWARD", "CONSUME", "TERMINAL").contains(scenario), "known crash scenario");
            require(Set.of("SNAPSHOT", "INDEX", "COMMIT").contains(boundary), "known crash boundary");
            details.addProperty("scenario", scenario); details.addProperty("boundary", boundary);
            if (operation.equals("write")) writeCrash(); else recoverCrash();
        }

        void writeCrash() {
            readyWorlds();
            act("native world sentinels", this::writeWorlds);
            login(a); login(b); join(a); join(b);
            act("first ready", () -> command(a, "hardrush ready"));
            waitFor("countdown", () -> round().phase() == RoundPhase.COUNTDOWN && idle());
            act("second ready", () -> command(b, "hardrush ready"));
            waitFor("both ready", () -> round().participants().get(b.profile.id()).ready() && idle());
            act("finish countdown", () -> advance(31));
            waitFor("both native humans admitted", () -> round().phase() == RoundPhase.RUNNING && idle()
                    && !PlayerProfiles.isLocked(a.player) && !PlayerProfiles.isLocked(b.player));
            if (scenario.equals("TERMINAL")) terminal(); else monsterCycle();
        }

        void monsterCycle() {
            act("private loaded native arena", () -> {
                var level = worlds.level(Level.OVERWORLD);
                level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                arena = Vec3.atBottomCenterOf(worlds.spawn().offset(160, 160 - worlds.spawn().getY(), 0));
                for (int x = -66; x <= 66; x++) for (int z = -66; z <= 66; z++) {
                    var feet = BlockPos.containing(arena).offset(x, 0, z);
                    level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
                    for (int y = 0; y < 3; y++) level.setBlock(feet.above(y), Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(feet.above(3), Blocks.STONE.defaultBlockState(), 2);
                }

            });
            act("place in private arena", () -> { move(a, worlds.level(Level.OVERWORLD), arena.add(10, 0, 0)); move(b, worlds.level(Level.OVERWORLD), arena); });
            act("native hardcore death", () -> require(a.player.hurtServer(a.player.level(), a.player.damageSources().genericKill(), Float.MAX_VALUE), "native death accepted"));
            waitFor("native monster lobby replacement", () -> {
                a.player = server.getPlayerList().getPlayer(a.profile.id());
                return idle() && round().participants().get(a.profile.id()).role() == ParticipantRole.MONSTER_LOBBY
                        && a.player.isAlive() && a.player.level() == LobbyWorld.open(server).holdingLevel() && profiles.isReady(a.profile.id(), round());
            });
            act("cooldown and formal deployment", () -> { advance(31); HuntInputFixtures.hunt(a.player, b.profile.id(), a.connection.packets); });
            waitFor("formal deployed zombie", () -> idle() && round().participants().get(a.profile.id()).role() == ParticipantRole.MONSTER_DEPLOYED
                    && MonsterRoles.isMonster(a.player) && !PlayerProfiles.isLocked(a.player));
            if (scenario.equals("AWARD")) arm();
            act("native monster lethal attack", () -> {
                move(a, b.player.level(), b.player.position().add(1, 0, 0));
                b.player.setHealth(1); b.player.setInvulnerableTime(0);
                require(MonsterRoles.attack(a.player, b.player) && b.player.isDeadOrDying(), "real monster attack produces lethal evidence");
            });
            if (scenario.equals("AWARD")) return;
            waitFor("award and victim profile settled", () -> {
                b.player = server.getPlayerList().getPlayer(b.profile.id());
                return idle() && round().participants().get(a.profile.id()).revivalEntitled()
                        && b.player.isAlive() && profiles.isReady(b.profile.id(), round());
            });
            act("native monster defeat returns entitled owner", () -> a.player.hurtServer(a.player.level(), a.player.damageSources().genericKill(), Float.MAX_VALUE));
            waitFor("entitled owner lobby", () -> {
                a.player = server.getPlayerList().getPlayer(a.profile.id());
                return idle() && round().participants().get(a.profile.id()).role() == ParticipantRole.MONSTER_LOBBY
                        && a.player.isAlive() && profiles.isReady(a.profile.id(), round());
            });
            arm();
            act("formal revival command", () -> command(a, "hardrush revive"));
        }

        void terminal() {
            act("native first dragon fight", () -> {
                move(a, worlds.level(Level.END), new Vec3(.5, 128, .5));
                fight = worlds.level(Level.END).getDragonFight(); fight.skipArenaLoadedCheck();
                worlds.level(Level.END).getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(0, 0), 3);
            });
            waitFor("first dragon spawned", () -> fight.dragonUUID() != null && worlds.level(Level.END).getEntity(fight.dragonUUID()) instanceof EnderDragon);
            arm();
            act("native dragon final hit", () -> {
                var dragon = (EnderDragon) worlds.level(Level.END).getEntity(fight.dragonUUID());
                dragon.snapTo(.5, 68, .5, 0, 0); dragon.setOldPosAndRot(); dragon.setNoAi(true);
                dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING); dragon.setHealth(.1f);
                a.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD)); a.player.attack(dragon.head);
                require(session.competitionLocked(), "native dragon final hit freezes competition");
            });
        }

        void arm() {
            waitFor("all earlier database work drained", () -> idle() && (int) get(session, "evidencePending") == 0
                    && (int) get(session, "progressPending") == 0 && (int) get(session, "queriesPending") == 0);
            act("durable native baseline and crash barrier", () -> {
                before = round();
                // Save a known baseline without cleanly stopping the running dedicated server.
                server.getPlayerList().saveAll(); server.saveAllChunks(false, true, true);
                details.addProperty("before", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(before)));
                details.addProperty("marker", marker.asLong()); details.addProperty("roundId", before.roundId().toString());
                var executor = (ExecutorService) get(session, "io");
                ioTask = CompletableFuture.runAsync(() -> installBarrier(), executor);
            });
            waitFor("barrier installed on owning SQLite thread", () -> { if (ioTask.isCompletedExceptionally()) ioTask.join(); return ioTask.isDone(); });
        }

        void installBarrier() {
            try {
                var store = (SqliteRoundStore) get(session, "store");
                var connection = (Connection) get(store, "connection");
                if (boundary.equals("COMMIT")) {
                    var callbacks = (Executor) get(session, "callbacks");
                    set(session, "callbacks", (Executor) work -> {
                        try {
                            if (armed.get() && RoundSnapshotCodec.decode(store.latest().orElseThrow().payload()).version() == before.version() + 1
                                    && armed.compareAndSet(true, false)) reached(store);
                        } catch (Exception error) { throw new CompletionException(error); }
                        callbacks.execute(work);
                    });
                } else {
                    Function.create(connection, "p3_server_crash", new Function() {
                        @Override protected void xFunc() { reached(store); }
                    });
                    String target = boundary.equals("SNAPSHOT") ? "AFTER UPDATE ON round_snapshots"
                            : scenario.equals("TERMINAL") ? "AFTER INSERT ON history_rounds"
                            : "AFTER UPDATE ON participant_records WHEN NEW.player_id='" + (scenario.equals("AWARD") ? b : a).profile.id() + "'";
                    try (var sql = connection.createStatement()) {
                        sql.execute("PRAGMA cache_size=1"); sql.execute("PRAGMA cache_spill=ON");
                        sql.execute("CREATE TABLE p3_crash_spill(payload BLOB)");
                        sql.execute("CREATE TEMP TRIGGER p3_server_boundary " + target + " BEGIN INSERT INTO p3_crash_spill VALUES(zeroblob(262144)); SELECT p3_server_crash(); END");
                    }
                }
                baselineWalBytes = Files.size(database.resolveSibling("p1.sqlite-wal"));
                armed.set(true);
            } catch (Exception error) { throw new CompletionException(error); }
        }

        void reached(SqliteRoundStore store) {
            try {
                long walBytes = Files.size(database.resolveSibling("p1.sqlite-wal"));
                require(walBytes > baselineWalBytes, "real committed or uncommitted WAL frames reached disk");
                details.addProperty("walBytesBefore", baselineWalBytes); details.addProperty("walBytesAtKill", walBytes);
                // A separate read connection sees only committed data, including within a blocked trigger.
                try (var read = new SqliteRoundStore(database)) {
                    expected = RoundSnapshotCodec.decode(read.latest().orElseThrow().payload());
                }
                require(session.snapshot().orElseThrow().equals(before), "main-thread commit callback has not applied native effects");
                require(expected.version() == before.version() + (boundary.equals("COMMIT") ? 1 : 0), "exact requested transaction boundary");
                if (!boundary.equals("COMMIT")) require(expected.equals(before), "uncommitted transaction leaves the full snapshot unchanged");
                else if (scenario.equals("AWARD")) {
                    require(expected.participants().get(a.profile.id()).revivalEntitled(), "native kill commits one qualification");
                    require(expected.participants().get(b.profile.id()).role() == ParticipantRole.MONSTER_LOBBY, "victim identity commits with qualification");
                } else if (scenario.equals("CONSUME")) {
                    var player = expected.participants().get(a.profile.id());
                    require(!player.revivalEntitled() && player.role() == ParticipantRole.HUMAN_ACTIVE
                            && player.lifeSequence() == before.participants().get(a.profile.id()).lifeSequence() + 1, "consumption commits exactly one new human life");
                } else require(expected.result() != null && expected.result().winner().equals(a.profile.id()), "native first dragon terminal commits once");
                details.addProperty("checkpoint", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(expected)));
                details.addProperty("status", "KILL_READY");
                Path pending = directory.resolve("kill-ready.pending");
                Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(details) + "\n");
                Files.move(pending, directory.resolve("kill-ready.json"), StandardCopyOption.ATOMIC_MOVE);
                for (;;) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
            } catch (Exception error) { throw new CompletionException(error); }
        }

        void recoverCrash() {
            act("check exact killed writer WAL checkpoint", () -> {
                try {
                    var data = JsonParser.parseString(Files.readString(directory.resolve("kill-ready.json"))).getAsJsonObject();
                    require(data.get("pid").getAsLong() != ProcessHandle.current().pid(), "fresh dedicated JVM");
                    expected = RoundSnapshotCodec.decode(Base64.getDecoder().decode(data.get("checkpoint").getAsString()));
                    var restored = round();
                    require(restored.phase() == RoundPhase.RECOVERY_REQUIRED && restored.timingUncertain(), "dirty dedicated server enters quarantine");
                    require(restored.roundId().equals(expected.roundId()) && restored.seed() == expected.seed()
                            && restored.rules().equals(expected.rules()) && restored.version() == expected.version(), "round identity and durable version preserved");
                    require(restored.result() == null ? expected.result() == null : restored.result().equals(expected.result()), "terminal preserved");
                    expected.participants().forEach((id, p) -> require(p.withSession(p.name(), false).equals(restored.participants().get(id)), "identity/qualification/life never replayed"));
                    require(session.competitionLocked() && service("WORLDS", dev.hardrush.world.RoundWorldBundle.class) == null, "old worlds not simulated");
                    details.addProperty("roundId", restored.roundId().toString());
                    details.addProperty("checkpoint", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(expected)));
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            quarantinedLogin(a); quarantinedLogin(b);
            waitFor("repeated quarantine ticks", () -> ++recoveryTicks >= 20);
            act("reject repeated consumption and new participation", () -> {
                command(a, "hardrush revive"); SpatialInputFixtures.closedGate(a.player);
                require(round().version() == expected.version() && round().challengeNanos() == expected.challengeNanos(), "recovery never consumes or advances time");
                for (var actor : List.of(a, b)) require(actor.player.level() == LobbyWorld.open(server).holdingLevel() && PlayerProfiles.isLocked(actor.player), "native reconnect remains in permanent lobby");
                stopping = true; server.halt(false);
            });
        }

        void quarantinedLogin(Actor actor) {
            act("prepare quarantined UUID", () -> { actor.prepare = new PrepareSpawnTask(server, new NameAndId(actor.profile)); actor.prepare.start(packet -> {}); });
            waitFor("load quarantined UUID", () -> actor.prepare.tick());
            act("connect quarantined UUID", () -> {
                actor.connection = new RecordingConnection(); actor.channel = new EmbeddedChannel(actor.connection);
                actor.player = actor.prepare.spawnPlayer(actor.connection, CommonListenerCookie.createInitial(actor.profile, false)); actor.prepare.close(); acknowledge(actor);
                require(actor.player.level() == LobbyWorld.open(server).holdingLevel() && PlayerProfiles.isLocked(actor.player), "login cannot restore uncertain native effects");
            });
        }

        @Override void stopped() {
            try {
                require(failure == null && stopping && steps.isEmpty() && operation.equals("recover"), "only completed recovery may stop normally");
                try (var store = new SqliteRoundStore(database)) {
                    store.checkIntegrity();
                    var saved = RoundSnapshotCodec.decode(store.latest().orElseThrow().payload());
                    require(Arrays.equals(RoundSnapshotCodec.encode(expected), store.latest().orElseThrow().payload()) && !saved.cleanShutdown(),
                            "normal stop preserves original dirty checkpoint; next process must quarantine again");
                    require(store.history(10, 0).size() == (expected.result() == null ? 0 : 1), "history neither duplicated nor invented");
                    details.addProperty("finalVersion", saved.version());
                }
                details.addProperty("status", "PASS");
            } catch (Throwable error) { details.addProperty("status", "FAIL"); details.addProperty("error", error.toString()); error.printStackTrace(); }
            try { Files.writeString(directory.resolve("recover-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(details) + "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
}
