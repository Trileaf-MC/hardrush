package dev.hardrush.gametest;

import static dev.hardrush.gametest.ServerRestartRegression.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.storage.FullCheckpointRuntime;
import dev.hardrush.storage.FullCheckpoint;
import dev.hardrush.storage.RecoveryRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.persistence.SqliteRoundStore;
import java.nio.file.Files;
import java.util.Base64;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Test mod only: a parent owns SIGKILL; production code owns save publication and startup rollback. */
public final class FullCheckpointRegression implements ModInitializer {
    @Override public void onInitialize() {
        if (System.getProperty(TestProperties.FULL_CHECKPOINT) == null) return;
        RoundRuntime.initialize();
        var fixture = new FullFixture[1];
        ServerLifecycleEvents.SERVER_STARTED.register(server -> fixture[0] = new FullFixture(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (fixture[0] != null) fixture[0].tick(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { if (fixture[0] != null) fixture[0].stopped(); });
    }

    private static final class FullFixture extends Fixture {
        long savedWorldClock;
        java.nio.file.Path onlineOlder, onlineNewer;
        String onlineTicket, staleTicket;
        Object recoveryService;
        boolean clickablePrompt;
        FullFixture(MinecraftServer server) {
            super(server, false);
            if (operation.equals("online")) onlineRecovery();
            else if (operation.equals("write")) writeFullPoint(); else recoverFullPoint();
        }

        void onlineRecovery() {
            readyWorlds(); act("online fixture worlds", this::writeWorlds);
            login(a); assemble(a); act("online fixture starts", () -> advance(31));
            waitFor("online human admitted", () -> idle() && round().phase() == RoundPhase.RUNNING && !PlayerProfiles.isLocked(a.player));
            act("online checkpoint inventory", () -> {
                state(a, 0); a.player.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 3));
                a.player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.STICK, 2));
                server.getPlayerList().op(new net.minecraft.server.players.NameAndId(a.profile),
                        java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.OWNERS)), java.util.Optional.empty());
                recoveryService = ((java.util.Map<?, ?>) getStatic(RecoveryRuntime.class, "SERVICES")).get(server);
            });
            waitFor("online first checkpoint ready", () -> session.canSaveFullCheckpoint() && profiles.readyForFullCheckpoint(round()));
            act("online first full point", () -> { onlineOlder = publishOnline(); savedWorldClock = worlds.level(Level.OVERWORLD).getOverworldClockTime(); });
            act("advance before second point", () -> advance(7));
            waitFor("second checkpoint ready", () -> session.canSaveFullCheckpoint() && profiles.readyForFullCheckpoint(round()));
            act("online second full point", () -> onlineNewer = publishOnline());
            act("fault after newer live progress", () -> {
                worlds.level(Level.OVERWORLD).setBlockAndUpdate(marker, Blocks.STONE.defaultBlockState());
                a.player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 42));
                session.adapterFailure("在线恢复回归注入故障", new IllegalStateException("fixture only"));
            });
            waitFor("fault body isolated", () -> LobbyWorld.isLobby(a.player.level()) && idle());
            act("recovery preflight command", () -> {
                var admin = server.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.ADMINS));
                require(RecoveryRuntime.request(admin) == 0, "OP3 cannot request a rollback");
                command(a, "hardrush admin recovery");
            });
            waitFor("newest point confirmation", () -> get(recoveryService, "ticket") != null);
            act("expired ticket cannot change state", () -> {
                String expired = get(recoveryService, "ticket").toString();
                set(recoveryService, "expires", System.nanoTime() - 1);
                require(RecoveryRuntime.decide(a.player.createCommandSourceStack(), "yes", expired) == 0, "expired yes is rejected");
                command(a, "hardrush admin recovery");
            });
            waitFor("fresh point after expiration", () -> get(recoveryService, "ticket") != null);
            act("chat click prompt and owner guard", () -> {
                onlineTicket = get(recoveryService, "ticket").toString();
                var point = (FullCheckpoint.Candidate) get(recoveryService, "candidate");
                require(point.id().toString().equals(onlineNewer.getFileName().toString()), "newest verified point offered first");
                require(RecoveryRuntime.decide(server.createCommandSourceStack(), "yes", onlineTicket) == 0, "another administrator cannot consume the ticket");
                require(get(recoveryService, "ticket").toString().equals(onlineTicket), "wrong owner does not consume ticket");
                server.getPlayerList().op(new net.minecraft.server.players.NameAndId(a.profile),
                        java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.ADMINS)), java.util.Optional.empty());
                require(RecoveryRuntime.decide(a.player.createCommandSourceStack(), "yes", onlineTicket) == 0, "permission revocation invalidates confirmation");
                server.getPlayerList().op(new net.minecraft.server.players.NameAndId(a.profile),
                        java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.OWNERS)), java.util.Optional.empty());
                command(a, "hardrush admin recovery no " + onlineTicket);
                require(!RecoveryRuntime.busy(server), "no cancels without restore");
                require(worlds.level(Level.OVERWORLD).getBlockState(marker).is(Blocks.STONE), "no keeps original world");
                command(a, "hardrush admin recovery");
            });
            waitFor("renewed confirmation", () -> get(recoveryService, "ticket") != null);
            act("change newest image after offer then confirm", () -> {
                onlineTicket = get(recoveryService, "ticket").toString(); staleTicket = onlineTicket;
                corrupt(onlineNewer);
                command(a, "hardrush admin recovery yes " + onlineTicket);
            });
            waitFor("automatic older fallback confirmation", () -> get(recoveryService, "ticket") != null);
            act("older rollback requires fresh consent", () -> {
                onlineTicket = get(recoveryService, "ticket").toString();
                var point = (FullCheckpoint.Candidate) get(recoveryService, "candidate");
                require(point.id().toString().equals(onlineOlder.getFileName().toString()), "damaged latest falls back to previous complete point");
                expected = point.round();
                require(RecoveryRuntime.decide(a.player.createCommandSourceStack(), "yes", staleTicket) == 0, "stale confirmation cannot restore another point");
                command(a, "hardrush admin recovery yes " + onlineTicket);
            });
            waitFor("online world and profile load completed", () -> {
                worlds = service("WORLDS", dev.hardrush.world.RoundWorldBundle.class);
                return !RecoveryRuntime.busy(server) && worlds != null && worlds.ready() && idle()
                        && !PlayerProfiles.isLocked(a.player) && round().phase() == RoundPhase.RUNNING;
            });
            act("same connection returns to exact checkpoint", () -> {
                checkWorlds(); checkState(a, 0);
                require(clickablePrompt, "native chat packet includes clickable yes and no commands");
                require(itemCount(Items.EMERALD) == 3 && itemCount(Items.STICK) == 2, "online cursor and crafting restored exactly once");
                require(round().roundId().equals(expected.roundId()) && round().timingUncertain(), "same round resumed with review marker");
                query = session.audits(round().roundId(), 20, 0);
            });
            waitFor("online audit readable", () -> query.isDone());
            act("restore was durably audited", () -> {
                @SuppressWarnings("unchecked") var rows = (java.util.List<dev.hardrush.core.record.AdminAudit>) query.join();
                require(rows.stream().anyMatch(row -> row.action().equals("RECOVER_CHECKPOINT")), "recovery audit survives rollback");
                try (var paths = Files.list(root.resolve("hardrush/full-checkpoints"))) {
                    for (var path : paths.filter(p -> Files.isRegularFile(p.resolve("complete.properties"))).toList()) corrupt(path);
                } catch (Exception error) { throw new IllegalStateException(error); }
                session.adapterFailure("all checkpoint copies damaged", new IllegalStateException("fixture only"));
            });
            waitFor("second fault quarantined", () -> LobbyWorld.isLobby(a.player.level()) && idle());
            act("all bad points command", () -> command(a, "hardrush admin recovery"));
            waitFor("all points rejected without offer", () -> get(recoveryService, "stage").toString().equals("IDLE"));
            act("bare close-recovery command", () -> { oldRound = round().roundId(); command(a, "hardrush admin close-recovery"); });
            waitFor("abandoned round rotated to new round", () -> !round().roundId().equals(oldRound) && round().sequence() == expected.sequence() + 1 && idle());
            readyWorlds();
            waitFor("new round ready without old equipment", () -> profiles.isReady(a.profile.id(), round()) && round().phase() == RoundPhase.WAITING && idle());
            act("new round is usable", () -> {
                require(a.player.getInventory().getItem(4).isEmpty(), "abandoned equipment did not enter new round");
                require(!Files.exists(root.resolve("hardrush/full-checkpoints/restoring")), "no old transaction can rewind the new round");
                stop();
            });
        }

        @Override void tick() {
            if (operation.equals("online") && a.connection != null) {
                for (var packet : a.connection.packets) if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) {
                    long buttons = chat.content().getSiblings().stream().filter(part -> part.getStyle().getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand).count();
                    if (buttons == 2 && chat.content().getString().contains("[yes]") && chat.content().getString().contains("[no]")) clickablePrompt = true;
                }
            }
            super.tick();
        }

        java.nio.file.Path publishOnline() {
            FullCheckpointRuntime.close(server);
            FullCheckpointRuntime.tick(server, session, worlds, profiles);
            try { return root.resolve("hardrush/full-checkpoints").resolve(Files.readString(root.resolve("hardrush/full-checkpoints/latest")).strip()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }

        void corrupt(java.nio.file.Path image) {
            try {
                var file = image.resolve("data").resolve(root.toAbsolutePath().normalize().relativize(dev.hardrush.world.RoundWorldPaths.world(root, round().roundId(), "overworld"))).resolve("region");
                try (var paths = Files.list(file)) {
                    Files.writeString(paths.filter(p -> p.toString().endsWith(".mca")).findFirst().orElseThrow(), "corrupt-online-fixture", java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        }

        void writeFullPoint() {
            readyWorlds(); act("write all three native world sentinels", this::writeWorlds);
            login(a); assemble(a);
            act("finish native preparation", () -> advance(31));
            waitFor("human admitted", () -> idle() && round().phase() == RoundPhase.RUNNING && !PlayerProfiles.isLocked(a.player));
            act("write inventory XP advancement effects and Nether position", () -> {
                state(a, 0);
                a.player.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 3));
                a.player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.STICK, 2));
                LobbyWorld.open(server).holdingLevel().save(null, true, false);
            });
            waitFor("all profile and SQLite writes drained", () -> session.canSaveFullCheckpoint() && profiles.readyForFullCheckpoint(round()));
            act("publish actual full native checkpoint", () -> {
                FullCheckpointRuntime.close(server);
                FullCheckpointRuntime.tick(server, session, worlds, profiles);
                try {
                    var saved = root.resolve("hardrush/full-checkpoints").resolve(Files.readString(root.resolve("hardrush/full-checkpoints/latest")).strip());
                    expected = SqliteRoundStore.inspectLatest(saved.resolve("data/hardrush/p1.sqlite")).orElseThrow();
                    require(expected.roundId().equals(round().roundId()), "actual automatic checkpoint belongs to current round");
                    require(expected.version() == round().version(), "new full checkpoint, not an older admission checkpoint");
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            act("persist newer world and inventory that must roll back together", () -> {
                worlds.level(Level.OVERWORLD).setBlockAndUpdate(marker, Blocks.STONE.defaultBlockState());
                a.player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 42));
                a.player.inventoryMenu.setCarried(ItemStack.EMPTY);
                worlds.save(); server.getPlayerList().saveAll(); advance(11);
            });
            waitFor("newer race checkpoint persisted", () -> idle() && round().version() > expected.version());
            act("signal parent to SIGKILL this exact JVM", () -> {
                var ready = new JsonObject();
                ready.addProperty("pid", ProcessHandle.current().pid());
                ready.addProperty("marker", marker.asLong());
                ready.addProperty("checkpoint", Base64.getEncoder().encodeToString(RoundSnapshotCodec.encode(expected)));
                ready.addProperty("newerVersion", round().version());
                ready.addProperty("worldClock", worlds.level(Level.OVERWORLD).getOverworldClockTime());
                try { Files.writeString(directory.resolve("writer-ready.json"), new GsonBuilder().setPrettyPrinting().create().toJson(ready)); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            waitFor("external SIGKILL only", () -> false);
        }

        void recoverFullPoint() {
            act("inspect restored round before native reconnect", () -> {
                try {
                    var ready = JsonParser.parseString(Files.readString(directory.resolve("writer-ready.json"))).getAsJsonObject();
                    require(ready.get("pid").getAsLong() != ProcessHandle.current().pid(), "recovery uses a new dedicated JVM");
                    expected = RoundSnapshotCodec.decode(Base64.getDecoder().decode(ready.get("checkpoint").getAsString()));
                    marker = BlockPos.of(ready.get("marker").getAsLong());
                    savedWorldClock = ready.get("worldClock").getAsLong();
                    require(round().roundId().equals(expected.roundId()), "same round UUID retained");
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            if (operation.equals("recover-corrupt")) {
                act("damaged full point keeps latest source facts and a clear reason", () -> {
                    require(round().phase() == RoundPhase.RECOVERY_REQUIRED, "corrupt checkpoint retains quarantine");
                    require(round().version() > expected.version(), "corrupt image did not rewind live SQLite");
                    require(RoundRuntime.recoveryNotice(server).contains("损坏"), "player receives specific corrupt-file reason");
                });
                nativeLogin(false);
                act("native reconnect remains in lobby", () -> {
                    require(LobbyWorld.isLobby(a.player.level()) && PlayerProfiles.isLocked(a.player), "unsafe player is not released");
                    session.requestJoin(a.profile.id(), a.profile.name(), reply -> require(!reply.success(), "assembly cannot bypass recovery"));
                    stop();
                });
                return;
            }
            act("verify phase counters and uncertainty", () -> {
                require(round().phase() == RoundPhase.RUNNING && round().timingUncertain(), "automatically resumed active round with review marker");
                require(round().challengeNanos() == expected.challengeNanos(), "shutdown and copy time do not accrue");
                require(round().participants().values().stream().noneMatch(Participant::online), "offline before profile proof");
            });
            readyWorlds(); act("check native world SavedData and containers", this::checkWorlds);
            login(a);
            waitFor("human restored automatically", () -> idle() && !PlayerProfiles.isLocked(a.player));
            act("full profile includes cursor item and original life", () -> {
                checkState(a, 0);
                require(itemCount(Items.EMERALD) == 3, "cursor stack retained exactly once across native dimension/menu closure");
                require(itemCount(Items.STICK) == 2, "crafting input retained exactly once");
                require(round().participants().get(a.profile.id()).lifeSequence() == expected.participants().get(a.profile.id()).lifeSequence(), "no extra life or revival charge consumed");
                a.player.connection.onDisconnect(new DisconnectionDetails(Component.literal("full checkpoint quick reconnect fixture")));
                a.channel.finishAndReleaseAll(); a.player = null;
            });
            waitFor("offline identity committed", () -> idle() && !round().participants().get(a.profile.id()).online());
            nativeLogin(true);
            waitFor("early-online reconnect still restores profile", () -> idle() && !PlayerProfiles.isLocked(a.player));
            act("reconnect retained round and inventory without preparation", () -> { checkState(a, 0); stop(); });
        }

        void nativeLogin(boolean earlyOnline) {
            act("begin native re-login", () -> { a.prepare = new net.minecraft.server.network.config.PrepareSpawnTask(server,
                    new net.minecraft.server.players.NameAndId(a.profile)); a.prepare.start(packet -> {}); });
            waitFor("native player file read", () -> a.prepare.tick());
            act("native connection established", () -> {
                a.connection = new RecordingConnection(); a.channel = new io.netty.channel.embedded.EmbeddedChannel(a.connection);
                a.player = a.prepare.spawnPlayer(a.connection, net.minecraft.server.network.CommonListenerCookie.createInitial(a.profile, false));
                a.prepare.close(); acknowledge(a);
                if (earlyOnline) session.onOnline(a.profile.id(), a.profile.name(), true);
            });
        }

        @Override void checkWorlds() {
            int index = 0;
            for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var level = worlds.level(dimension); level.getChunk(marker);
                require(level.getSeed() == expected.seed(), "same native seed");
                require(level.getBlockState(marker).is(Blocks.EMERALD_BLOCK), "checkpoint construction restored instead of later stone");
                var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(marker.east());
                require(chest != null && chest.getItem(0).is(Items.AMETHYST_SHARD) && chest.getItem(0).getCount() == ++index,
                        "native container contents restored in all three dimensions");
            }
            require(worlds.level(Level.OVERWORLD).getOverworldClockTime() == savedWorldClock, "checkpoint SavedData clock restored");
            require(worlds.level(Level.OVERWORLD).getWeatherData().isRaining(), "checkpoint weather restored");
        }

        int itemCount(net.minecraft.world.item.Item item) {
            int total = a.player.inventoryMenu.getCarried().is(item) ? a.player.inventoryMenu.getCarried().getCount() : 0;
            for (int i = 0; i < a.player.getInventory().getContainerSize(); i++) {
                var stack = a.player.getInventory().getItem(i); if (stack.is(item)) total += stack.getCount();
            }
            for (int i = 0; i < a.player.inventoryMenu.getCraftSlots().getContainerSize(); i++) {
                var stack = a.player.inventoryMenu.getCraftSlots().getItem(i); if (stack.is(item)) total += stack.getCount();
            }
            return total;
        }

        @Override void stopped() {
            try {
                if (failure != null) throw new IllegalStateException(failure);
                require(stopping && steps.isEmpty(), "all checkpoint assertions completed before normal recovery stop");
                require(!escapedCallbacks.get(), "native effects stayed on main thread");
                details.addProperty("status", "PASS");
            } catch (Throwable error) { details.addProperty("status", "FAIL"); details.addProperty("failure", error.toString()); }
            try { Files.writeString(directory.resolve(operation + "-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(details)); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }
    }
}
