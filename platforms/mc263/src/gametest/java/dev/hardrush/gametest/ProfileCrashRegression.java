package dev.hardrush.gametest;

import static dev.hardrush.gametest.ServerRestartRegression.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.player.ProfileJournal;
import dev.hardrush.core.round.RoundSnapshotCodec;
import dev.hardrush.persistence.SqliteRoundStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import io.netty.channel.embedded.EmbeddedChannel;

/** Test mod only. JDI suspends the actual journal worker immediately after a durable method returns. */
public final class ProfileCrashRegression implements ModInitializer {
    public static final long PID = ProcessHandle.current().pid();

    @Override public void onInitialize() {
        if (System.getProperty(TestProperties.PROFILE_CRASH_BOUNDARY) == null) return;
        RoundRuntime.initialize();
        var fixture = new ProfileFixture[1];
        ServerLifecycleEvents.SERVER_STARTED.register(server -> fixture[0] = new ProfileFixture(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (fixture[0] != null) fixture[0].tick(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (fixture[0] != null) fixture[0].stopped(); });
    }

    private static final class ProfileFixture extends Fixture {
        final String boundary = System.getProperty(TestProperties.PROFILE_CRASH_BOUNDARY);
        int recoveryTicks;

        ProfileFixture(MinecraftServer server) {
            super(server, false);
            require(Set.of("PREPARED", "SUSPENDED", "APPLIED", "VERIFIED").contains(boundary), "known profile boundary");
            details.addProperty("scenario", "PROFILE"); details.addProperty("boundary", boundary);
            if (operation.equals("write")) writeProfile(); else recoverProfile();
        }

        void writeProfile() {
            readyWorlds();
            act("native world sentinels", this::writeWorlds);
            // Fully initialize one real login before attaching JDI, so the journal class is loaded.
            login(b);
            act("prepare native baseline UUID", () -> { a.prepare = new PrepareSpawnTask(server, new NameAndId(a.profile)); a.prepare.start(packet -> {}); });
            waitFor("load native baseline UUID", () -> a.prepare.tick());
            act("seed pre-HardRush native player file", () -> {
                try {
                    a.connection = new RecordingConnection(); a.channel = new EmbeddedChannel(a.connection);
                    // Explicit test-artifact scope prepares a vanilla checkpoint before HardRush manages this UUID.
                    dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> a.player = a.prepare.spawnPlayer(a.connection, CommonListenerCookie.createInitial(a.profile, false)));
                    a.prepare.close(); acknowledge(a);
                    var player = a.player;
                    player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 13));
                    player.getEnderChestInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 17));
                    player.totalExperience = 100; player.experienceLevel = 7;
                    a.connection.disconnect(net.minecraft.network.chat.Component.literal("P3 native baseline fixture"));
                    a.connection.handleDisconnection(); a.channel.finishAndReleaseAll(); a.player = null;
                    var loaded = server.getPlayerList().loadPlayerData(new NameAndId(a.profile)).orElseThrow();
                    require(loaded.getListOrEmpty("Inventory").size() == 1 && loaded.getIntOr("XpTotal", 0) == 100,
                            "native player storage reads the seeded original before conversion");
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            waitFor("all database work drained", () -> idle() && (int) get(session, "evidencePending") == 0
                    && (int) get(session, "progressPending") == 0 && (int) get(session, "queriesPending") == 0);
            act("save world and arm exact profile boundary", () -> {
                try {
                    server.getPlayerList().saveAll(); server.saveAllChunks(false, true, true);
                    expected = round();
                    require(!expected.cleanShutdown(), "live server has dirty checkpoint");
                    details.addProperty("before", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(expected)));
                    details.addProperty("checkpoint", details.get("before").getAsString());
                    details.addProperty("roundId", expected.roundId().toString());
                    details.addProperty("playerId", a.profile.id().toString());
                    Files.writeString(directory.resolve("profile-baseline.json"), new GsonBuilder().setPrettyPrinting().create().toJson(details) + "\n");
                    var properties = new Properties();
                    properties.setProperty("pid", Long.toString(PID));
                    properties.setProperty("boundary", boundary);
                    properties.setProperty("playerId", a.profile.id().toString());
                    try (var out = Files.newOutputStream(directory.resolve("profile-arm.properties"))) { properties.store(out, "JDI fixture identity"); }
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            waitFor("parent debugger owns exact worker boundary", () -> Files.isRegularFile(directory.resolve("profile-debugger-ready.json")));
            // This is a full native login. The production tick starts its real archive/reset plan.
            login(a);
            act("unreachable after journal barrier", () -> { throw new AssertionError("Parent must SIGKILL at journal boundary"); });
        }

        void recoverProfile() {
            act("dirty world and exact profile checkpoint retained", () -> {
                try {
                    var data = JsonParser.parseString(Files.readString(directory.resolve("kill-ready.json"))).getAsJsonObject();
                    require(data.get("pid").getAsLong() != PID, "fresh dedicated server JVM");
                    expected = RoundSnapshotCodec.decode(Base64.getDecoder().decode(data.get("checkpoint").getAsString()));
                    require(round().phase() == RoundPhase.RECOVERY_REQUIRED && round().timingUncertain()
                            && session.competitionLocked(), "interrupted server remains quarantined");
                    require(round().roundId().equals(expected.roundId()) && round().version() == expected.version()
                            && round().seed() == expected.seed() && round().rules().equals(expected.rules()), "original generation preserved");
                    require(service("WORLDS", dev.hardrush.world.RoundWorldBundle.class) == null, "old worlds not simulated");
                    verifyJournal();
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            quarantinedLogin(a); quarantinedLogin(b);
            waitFor("quarantine remains stable", () -> ++recoveryTicks >= 20);
            act("reconnect cannot repeat reset or enter competition", () -> {
                SpatialInputFixtures.closedGate(a.player); command(a, "hardrush revive");
                require(round().version() == expected.version() && round().challengeNanos() == expected.challengeNanos(), "no new reward, identity or time");
                require(a.player.getInventory().getItem(4).is(Items.DIAMOND)
                        && a.player.getInventory().getItem(4).getCount() == 13, "old native inventory stays quarantined without reset");
                require(a.player.getEnderChestInventory().getItem(0).is(Items.GOLD_INGOT)
                        && a.player.getEnderChestInventory().getItem(0).getCount() == 17
                        && a.player.totalExperience == 100, "old ender storage and experience remain quarantined");
                stopping = true; server.halt(false);
            });
        }

        void quarantinedLogin(Actor actor) {
            act("prepare quarantined UUID", () -> { actor.prepare = new PrepareSpawnTask(server, new NameAndId(actor.profile)); actor.prepare.start(packet -> {}); });
            waitFor("load quarantined UUID", () -> actor.prepare.tick());
            act("connect quarantined UUID", () -> {
                actor.connection = new RecordingConnection(); actor.channel = new EmbeddedChannel(actor.connection);
                actor.player = actor.prepare.spawnPlayer(actor.connection, CommonListenerCookie.createInitial(actor.profile, false)); actor.prepare.close(); acknowledge(actor);
                require(actor.player.level() == LobbyWorld.open(server).holdingLevel() && PlayerProfiles.isLocked(actor.player), "native login stays locked in permanent lobby");
            });
        }

        void verifyJournal() throws Exception {
            var journal = new ProfileJournal(root.resolve("hardrush/profiles"));
            var generation = new ProfileJournal.Generation(expected.roundId(), expected.sequence());
            var transition = journal.transition(a.profile.id()).orElseThrow();
            var phase = boundary.equals("SUSPENDED") ? ProfileJournal.Phase.PREPARED : ProfileJournal.Phase.valueOf(boundary);
            require(transition.target().equals(generation) && transition.phase() == phase, "exact journal write window reached");
            var old = NbtIo.readCompressed(new ByteArrayInputStream(journal.archived(a.profile.id(), Optional.empty())), NbtAccounter.unlimitedHeap());
            require(old.getListOrEmpty("Inventory").size() == 1 && old.getListOrEmpty("EnderItems").size() == 1
                    && old.getIntOr("XpTotal", 0) == 100, "original inventory/ender storage/experience sealed");
            if (boundary.equals("PREPARED")) {
                require(journal.suspended(a.profile.id(), generation).isEmpty(), "PREPARED has no reset output");
                boolean blocked = false;
                try { journal.recovery(a.profile.id(), generation); }
                catch (java.io.IOException error) { blocked = error.getMessage().contains("retain quarantine"); }
                require(blocked, "missing target snapshot cannot authorize another destructive reset");
            } else {
                var target = NbtIo.readCompressed(new ByteArrayInputStream(journal.recovery(a.profile.id(), generation).snapshot()), NbtAccounter.unlimitedHeap());
                require(target.getBooleanOr("hardrush_fresh_profile", false) && target.getListOrEmpty("Inventory").isEmpty()
                        && target.getListOrEmpty("EnderItems").isEmpty() && target.getIntOr("XpTotal", -1) == 0, "one complete empty reset output");
                require(target.getStringOr("hardrush_round_id", "").equals(expected.roundId().toString())
                        && target.getLongOr("hardrush_generation", 0) == expected.sequence()
                        && !target.getStringOr("hardrush_suspension_id", "").isBlank(), "target has exact durable generation and reset identity");
                details.addProperty("suspensionId", target.getStringOr("hardrush_suspension_id", ""));
            }
        }

        @Override void stopped() {
            try {
                if (failure != null) throw new IllegalStateException("Recovery fixture failed", failure);
                require(failure == null && stopping && steps.isEmpty() && operation.equals("recover"), "only verified recovery stops normally");
                require(!escapedCallbacks.get(), "profile callbacks stay on server thread");
                for (var actor : List.of(a, b)) require(PlayerProfiles.isLocked(actor.player), "no recovery unlock");
                verifyJournal();
                try (var store = new SqliteRoundStore(root.resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE))) {
                    store.checkIntegrity();
                    require(Arrays.equals(RoundSnapshotCodec.encode(expected), store.latest().orElseThrow().payload()), "original dirty checkpoint is immutable");
                    require(store.history(10, 0).isEmpty(), "conversion cannot invent a terminal");
                }
                details.addProperty("roundId", expected.roundId().toString());
                details.addProperty("status", "PASS");
            } catch (Throwable error) { details.addProperty("status", "FAIL"); details.addProperty("error", error.toString()); error.printStackTrace(); }
            try { Files.writeString(directory.resolve("recover-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(details) + "\n"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
}
