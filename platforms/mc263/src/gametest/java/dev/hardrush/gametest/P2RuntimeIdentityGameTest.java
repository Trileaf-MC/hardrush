package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.*;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import dev.hardrush.persistence.SqliteRoundStore;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Dedicated opt-in server run: production listeners and storage, with an embedded native peer. */
public final class P2RuntimeIdentityGameTest {
    @GameTest(maxTicks = 40000)
    public void nativeRuntimeAllowsSpawnSurvivalAndExtremeDeathBeforeMonsterReconnect(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p2.testIdentity")) { test.succeed(); return; }
        test.assertTrue(Boolean.getBoolean("hardrush.p1.enabled"), "The dedicated run requires the real enabled runtime");
        var fixture = new Fixture(test);
        var offlineHuman = new Fixture(test, "P2OfflineHuman");
        test.onEachTick(() -> {
            if (fixture.clock != null) fixture.clock.accumulateAndGet(System.nanoTime(), Math::max);
            fixture.acknowledge();
            offlineHuman.acknowledge();
            test.assertFalse(P1LobbyRuntime.sessionState(fixture.server).filter(state -> state == P1LobbySession.State.FAILED).isPresent(),
                    "Production runtime failed: " + P1LobbyRuntime.status(fixture.server));
        });
        var sequence = test.startSequence()
                .thenWaitUntil(() -> fixture.await(P1LobbyRuntime.sessionState(fixture.server)
                        .filter(state -> state == P1LobbySession.State.OPEN).isPresent(), "Runtime bootstrap"))
                .thenExecute(fixture::installClock)
                .thenExecute(() -> {
                    offlineHuman.session = fixture.session;
                    offlineHuman.profiles = fixture.profiles;
                });
        fixture.login(sequence);
        offlineHuman.login(sequence);
        sequence.thenWaitUntil(() -> fixture.await(fixture.round().phase() == RoundPhase.WAITING
                        && !fixture.session.mutationPending(), "Independent round is ready"))
                .thenExecute(() -> fixture.command("hardrush join"))
                .thenWaitUntil(() -> fixture.await(fixture.participant() != null && !fixture.session.mutationPending(), "Native join is durable"))
                .thenExecute(() -> offlineHuman.command("hardrush join"))
                .thenWaitUntil(() -> fixture.await(offlineHuman.participant() != null && !fixture.session.mutationPending(), "Second native join is durable"))
                .thenExecute(() -> fixture.command("hardrush ready"))
                .thenWaitUntil(() -> fixture.await(fixture.round().phase() == RoundPhase.COUNTDOWN && !fixture.session.mutationPending(), "Hardcore countdown is admitted"))
                .thenExecute(() -> offlineHuman.command("hardrush ready"))
                .thenWaitUntil(() -> fixture.await(offlineHuman.participant().ready() && !fixture.session.mutationPending(), "Second human is ready"))
                .thenExecute(() -> fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> fixture.await(fixture.round().phase() == RoundPhase.RUNNING && !fixture.session.mutationPending()
                        && !P1PlayerProfiles.isLocked(fixture.player) && !P1PlayerProfiles.isLocked(offlineHuman.player), "Production runtime unlocks both hardcore humans"))
                .thenExecute(offlineHuman::disconnect)
                .thenWaitUntil(() -> fixture.await(!offlineHuman.participant().online()
                        && offlineHuman.participant().role() == ParticipantRole.HUMAN_ACTIVE && !fixture.session.mutationPending(),
                        "A genuinely admitted offline human preserves the round during this identity-only regression"))
                .thenExecute(fixture::testUnprotectedSpawn)
                .thenExecute(fixture::leaveAndDie)
                .thenWaitUntil(() -> fixture.await(fixture.monsterReady(), "Production death listener replaces the dead human in the monster lobby"))
                .thenExecute(() -> {
                    fixture.player = fixture.server.getPlayerList().getPlayer(fixture.profile.id());
                    fixture.assertMonster();
                    fixture.assertStoredIdentity();
                    fixture.disconnect();
                })
                .thenWaitUntil(() -> fixture.await(!fixture.participant().online() && !fixture.session.mutationPending(), "Native disconnect preserves an offline monster identity"));
        fixture.login(sequence);
        sequence.thenWaitUntil(() -> fixture.await(fixture.monsterReady() && fixture.participant().online()
                        && !fixture.session.mutationPending(), "Native UUID reconnect restores the committed monster lobby"))
                .thenExecute(() -> fixture.command("hardrush join"))
                .thenWaitUntil(() -> fixture.await(!fixture.session.mutationPending(), "Repeated join has been handled"))
                .thenExecute(() -> {
                    fixture.assertMonster();
                    fixture.assertStoredIdentity();
                    fixture.disconnect();
                }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final GameProfile profile;
        P1LobbySession session;
        P1PlayerProfiles profiles;
        AtomicLong clock;
        PrepareSpawnTask prepare;
        RecordingConnection connection;
        EmbeddedChannel channel;
        ServerPlayer player;
        ServerPlayer dead;
        ServerLevel roundLevel;
        Vec3 outside;
        AABB drops;
        int acknowledged = Integer.MIN_VALUE;
        Fixture(GameTestHelper test) { this(test, "P2RuntimeHuman"); }
        Fixture(GameTestHelper test, String name) {
            this.test = test; server = test.getLevel().getServer();
            profile = new GameProfile(UUID.randomUUID(), name);
        }

        void installClock() {
            session = service("SESSIONS", P1LobbySession.class);
            profiles = service("PROFILES", P1PlayerProfiles.class);
            test.assertValueEqual(round().sequence(), 1L, "Dedicated storage starts at its first round");
            test.assertValueEqual(round().rules().mode(), GameMode.HARDCORE, "The actual initial rules are hardcore");
            clock = new AtomicLong(System.nanoTime());
            try {
                var field = P1LobbySession.class.getDeclaredField("clock"); field.setAccessible(true);
                field.set(session, (MonotonicClock) clock::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }

        RoundSnapshot round() { return P1LobbyRuntime.snapshot(server).orElseThrow(); }
        Participant participant() { return round().participants().get(profile.id()); }
        void await(boolean condition, String message) {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + P1LobbyRuntime.status(server));
        }

        void login(GameTestSequence sequence) {
            sequence.thenExecute(() -> {
                prepare = new PrepareSpawnTask(server, new NameAndId(profile)); prepare.start(packet -> {});
            }).thenWaitUntil(() -> await(prepare.tick(), "Native UUID player-data load"))
                    .thenExecute(() -> {
                        connection = new RecordingConnection(); channel = new EmbeddedChannel(connection);
                        acknowledged = Integer.MIN_VALUE;
                        player = prepare.spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false));
                        prepare.close(); acknowledge();
                        test.assertTrue(player.level() == P1LobbyWorld.open(server).holdingLevel() && P1PlayerProfiles.isLocked(player),
                                "Production native login remains in the lobby while the profile is verified");
                    }).thenWaitUntil(() -> await(profiles.isReady(profile.id(), round()), "Production profile journal is ready"));
        }

        void testUnprotectedSpawn() {
            var worlds = service("WORLDS", P1RoundWorldBundle.class);
            roundLevel = worlds.level(Level.OVERWORLD);
            test.assertTrue(player.level() == roundLevel && !P2ProtectionRuntime.protectedEntity(player), "The native human starts on unprotected natural terrain");
            test.assertTrue(P2ProtectionRuntime.zone(P1LobbyWorld.open(server).holdingLevel()).filter(P2ProtectionRuntime.Zone::lobby).isPresent(), "The long-lived lobby remains protected");
            for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                test.assertTrue(P2ProtectionRuntime.zone(worlds.level(dimension)).isEmpty(), "No formal round dimension registers a spawn protection zone");
            }
            test.assertFalse(P2ProtectionRuntime.rejectInteraction(player, roundLevel, worlds.spawn().below()), "Spawn building and interactions use ordinary survival rules");
            float health = player.getHealth();
            test.assertTrue(player.hurtServer(roundLevel, player.damageSources().genericKill(), 1), "Native damage is allowed immediately at spawn");
            test.assertValueEqual(player.getHealth(), health - 1, "Spawn has no area-based damage immunity");
            test.assertValueEqual(participant().role(), ParticipantRole.HUMAN_ACTIVE, "Nonlethal spawn damage preserves the human identity");
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 6));
            player.awardStat(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.JUMP), 11);
            var advancement = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            advancement.value().criteria().keySet().forEach(criterion -> player.getAdvancements().award(advancement, criterion));
            outside = findNaturalStanding(worlds);
            move(outside);
            test.assertTrue(player.position().distanceTo(outside) < .01, "Leaving spawn needs no durable exit permission");
        }

        Vec3 findNaturalStanding(P1RoundWorldBundle worlds) {
            for (int distance = 2; distance <= 12; distance++) for (var direction : Direction.Plane.HORIZONTAL) {
                var column = worlds.spawn().relative(direction, distance);
                for (int dy = -12; dy <= 12; dy++) {
                    BlockPos feet = column.offset(0, dy, 0);
                    if (P1RoundWorldBundle.safeStanding(roundLevel, feet)) return Vec3.atBottomCenterOf(feet);
                }
            }
            throw new IllegalStateException("The round has no nearby loaded natural standing point");
        }

        void leaveAndDie() {
            move(outside);
            test.assertTrue(player.position().distanceTo(outside) < .01 && !P2ProtectionRuntime.protectedEntity(player), "Movement reaches a natural standing point");
            var worlds = service("WORLDS", P1RoundWorldBundle.class);
            move(Vec3.atBottomCenterOf(worlds.spawn()));
            test.assertTrue(player.position().distanceTo(Vec3.atBottomCenterOf(worlds.spawn())) < .01, "The same human life can freely return to spawn");
            test.assertFalse(participant().hasLeftProtection(), "Ordinary movement never writes the obsolete exit restriction");
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            drops = new AABB(BlockPos.containing(player.position())).inflate(4);
            dead = player;
            test.assertTrue(player.hurtServer(roundLevel, player.damageSources().genericKill(), Float.MAX_VALUE), "Native lethal damage at spawn actually kills the human");
            test.assertTrue(dead.isDeadOrDying() && P1PlayerProfiles.isLocked(dead), "Production AFTER_DEATH immediately holds the dead native body");
            test.assertValueEqual(diamonds(), 3, "Vanilla drops all three human diamonds before the committed monster conversion");
            dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            test.assertTrue(server.getPlayerList().getPlayer(profile.id()) == dead && dead.connection.player == dead
                            && dead.isDeadOrDying(),
                    "A real respawn packet cannot replace the dead human before its hardcore identity commits");
        }

        boolean monsterReady() {
            var current = server.getPlayerList().getPlayer(profile.id());
            return participant() != null && participant().role() == ParticipantRole.MONSTER_LOBBY && current != null
                    && current != dead && current.isAlive() && current.level() == P1LobbyWorld.open(server).holdingLevel()
                    && profiles.isReady(profile.id(), round()) && !session.mutationPending();
        }

        void assertMonster() {
            player = server.getPlayerList().getPlayer(profile.id());
            test.assertValueEqual(round().phase(), RoundPhase.RUNNING, "An offline human prevents elimination without contributing an online racer");
            test.assertValueEqual(round().pauseReason(), PauseReason.NO_ONLINE_HUMANS, "Monster-only connections do not keep the human challenge clock running");
            test.assertValueEqual(participant().role(), ParticipantRole.MONSTER_LOBBY, "The committed UUID remains a monster");
            test.assertValueEqual(participant().lifeSequence(), 1, "Death and reconnect do not create another human life");
            test.assertFalse(participant().hasLeftProtection(), "Death conversion needs no legacy spawn exit marker");
            test.assertTrue(player.isAlive() && player.level() == P1LobbyWorld.open(server).holdingLevel() && P1PlayerProfiles.isLocked(player), "A living native monster remains isolated in the actual lobby");
            test.assertTrue(P1PlayerProfiles.canMoveInLobby(player), "The verified monster lobby permits native movement");
            test.assertValueEqual(player.gameMode.getGameModeForPlayer(), net.minecraft.world.level.GameType.ADVENTURE,
                    "Native monster respawn and reconnect use adventure mode");
            test.assertTrue(player.connection.player == player,
                    "The native connection must bind the replacement monster, not the removed dead human: listed="
                            + identity(player) + ",listener=" + identity(player.connection.player) + ",dead=" + identity(dead));
            test.assertTrue(player.getInventory().isEmpty(), "Native respawn and reconnect cannot duplicate the dropped human inventory");
            test.assertValueEqual(player.getEnderChestInventory().getItem(0).getCount(), 6, "This round's ender storage survives death and native reconnect");
            var advancement = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            test.assertTrue(player.getAdvancements().getOrStartProgress(advancement).isDone(), "This round's native completed advancement survives");
            test.assertValueEqual(player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.JUMP)), 11,
                    "Actual native cumulative statistics survive monster conversion and PrepareSpawnTask reconnect");
            test.assertValueEqual(diamonds(), 3, "Exactly one set of native death drops remains in the original round");
        }

        void assertStoredIdentity() {
            try (var store = new SqliteRoundStore(server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"))) {
                var saved = RoundSnapshotCodec.decode(store.load(round().roundId()).orElseThrow().payload()).participants().get(profile.id());
                test.assertValueEqual(saved.role(), ParticipantRole.MONSTER_LOBBY, "The real SQLite snapshot stores the monster role");
                test.assertTrue(!saved.hasLeftProtection() && saved.lifeSequence() == 1, "The real SQLite snapshot preserves identity without requiring a spawn exit");
                var record = store.participantRecord(round().roundId(), profile.id()).orElseThrow();
                test.assertValueEqual(record.deathCount(), 1, "Actual death listeners produce one persisted death");
                test.assertValueEqual(record.revivalCount(), 0, "A monster respawn is never a human revival");
                test.assertTrue(record.humanNanos() >= TimeUnit.SECONDS.toNanos(2), "The final human interval remains in persisted history");
                test.assertTrue(record.advancementCount() >= 1, "The actual progress listener retains admitted human history");
            } catch (Exception error) { throw new IllegalStateException(error); }
        }

        int diamonds() {
            return roundLevel.getEntitiesOfClass(ItemEntity.class, drops).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
        }
        void command(String text) {
            try { server.getCommands().getDispatcher().execute(text, player.createCommandSourceStack()); }
            catch (Exception error) { throw new IllegalStateException(text, error); }
        }
        void move(Vec3 target) { player.teleport(new TeleportTransition(roundLevel, target, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING)); acknowledge(); }
        void acknowledge() {
            if (connection == null) return;
            var current = server.getPlayerList().getPlayer(profile.id());
            if (current == null) return;
            connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance).map(ClientboundPlayerPositionPacket.class::cast)
                    .reduce((previous, latest) -> latest).filter(packet -> packet.id() != acknowledged).ifPresent(packet -> {
                        acknowledged = packet.id();
                        current.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(packet.id(),
                                current.getX(), current.getY(), current.getZ(), current.getYRot(), current.getXRot()));
                        current.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                    });
        }
        void disconnect() {
            if (player == null) return;
            connection.disconnect(Component.literal("P2 headless native reconnect")); connection.handleDisconnection();
            var listed = server.getPlayerList().getPlayer(profile.id());
            boolean managed = profiles.manages(player);
            String details = ": listed=" + identity(listed) + ",expected=" + identity(player)
                    + ",listener=" + identity(player.connection.player) + ",profileManaged=" + managed;
            test.assertTrue(listed == null, "Native disconnect removes the UUID from PlayerList" + details);
            test.assertFalse(managed, "Native disconnect removes the replacement's profile binding" + details);
            channel.finishAndReleaseAll(); player = null;
        }
        String identity(ServerPlayer value) {
            return value == null ? "null" : Integer.toHexString(System.identityHashCode(value)) + "[alive="
                    + value.isAlive() + ",removed=" + value.isRemoved() + ",level=" + value.level().dimension().identifier() + "]";
        }
        <T> T service(String name, Class<T> type) {
            try {
                var field = P1LobbyRuntime.class.getDeclaredField(name); field.setAccessible(true);
                return type.cast(((Map<?, ?>) field.get(null)).get(server));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record);
            else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
