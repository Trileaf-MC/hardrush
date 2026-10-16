package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.book.P1BookPort;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p2.lobby.P2LobbyUi;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real native login, movement packets and entity tracking, with two embedded connections and no client. */
public final class P1LobbyMovementGameTest {
    @GameTest(maxTicks = 60000)
    public void sharedAdventureLobbyTracksMovementAndKeepsRecoveryAndCompetitionIsolated(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.lobby.testMovement")) { test.succeed(); return; }
        var f = new Fixture(test);
        test.onEachTick(() -> { f.clock.accumulateAndGet(System.nanoTime(), Math::max); f.a.tickConnection(); f.b.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(
                P1LobbyWorld.open(f.server).ready() && P1LobbyRuntime.sessionState(f.server)
                    .filter(state -> state == P1LobbySession.State.OPEN).isPresent(), "Shared lobby ready"))
                .thenExecute(f::bind);
        f.a.login(sequence);
        f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(P1LobbyRuntime.snapshot(f.server).orElseThrow().phase() == RoundPhase.WAITING
                        && !f.session.mutationPending(), "Round adapters ready"))
                .thenWaitUntil(() -> f.await(f.b.connection.packets.stream().anyMatch(p -> p instanceof ClientboundAddEntityPacket add
                        && add.getId() == f.a.player.getId()), "Native entity tracker introduces the first peer to the second"))
                .thenExecute(() -> {
                    f.assertLobby();
                    test.assertTrue(f.a.player.level() != f.server.overworld(), "The original overworld is preserved separately");
                    test.assertTrue(f.a.player.level().getChunkSource().getGenerator() instanceof FlatLevelSource, "Actual native flat generator");
                    test.assertTrue(f.a.player.level().getBlockState(BlockPos.containing(f.a.player.position()).below()).is(Blocks.GRASS_BLOCK), "Flat grass spawn floor");
                    f.b.observedFrom = f.b.connection.packets.size();
                    f.a.move(.6, 0, 45, 15, true);
                    test.assertValueEqual(f.a.player.getYRot(), 45f, "The native move packet updates yaw");
                })
                .thenWaitUntil(() -> f.await(f.b.seesMovement(f.a.player, true, true), "The other connection receives actual position and rotation updates"))
                .thenExecute(() -> {
                    f.a.player.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(f.a.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
                    test.assertTrue(f.a.player.isSprinting(), "Lobby sprint command reaches vanilla");
                    f.a.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(true, false, false, false, true, false, true)));
                    test.assertTrue(f.a.player.getLastClientInput().jump(), "Lobby jump input reaches vanilla");
                    f.b.observedFrom = f.b.connection.packets.size();
                    f.a.move(.2, .42, 90, 0, false);
                })
                .thenWaitUntil(() -> f.await(f.b.seesMovement(f.a.player, true, false), "Native jump movement reaches the observer"))
                .thenExecute(() -> {
                    f.a.player.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(f.a.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
                    f.a.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
                    test.assertTrue(f.a.player.isShiftKeyDown() && !f.a.player.isSprinting(), "Sneak and stop-sprinting reach vanilla");
                    f.assertProtectionAndUi();
                    var position = f.a.player.position();
                    f.profiles.returnToLobby(f.a.player); f.profiles.returnToLobby(f.a.player);
                    test.assertTrue(f.a.player.position().distanceToSqr(position) < .000001, "Repeated return checks do not reset a normal lobby position");
                    f.a.command("hardrush join");
                })
                .thenWaitUntil(() -> f.await(!f.session.mutationPending(), "Join committed"))
                .thenExecute(() -> f.a.command("hardrush ready"))
                .thenWaitUntil(() -> f.await(P1LobbyRuntime.snapshot(f.server).orElseThrow().phase() == RoundPhase.COUNTDOWN
                        && !f.session.mutationPending(), "Countdown committed"))
                .thenExecute(() -> { f.assertLobby(); f.a.move(.2, 0, 180, 0, false); f.a.disconnect(); });
        f.a.login(sequence);
        sequence.thenWaitUntil(() -> f.await(!f.session.mutationPending() && P1LobbyRuntime.snapshot(f.server).orElseThrow()
                        .participants().get(f.a.profile.id()).online(), "Reconnect online committed"))
                .thenExecute(() -> { f.assertLobby(); f.a.command("hardrush ready"); })
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && P1LobbyRuntime.snapshot(f.server).orElseThrow()
                        .participants().get(f.a.profile.id()).ready(), "Reconnected peer readied"))
                .thenExecute(() -> f.b.command("hardrush join"))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending(), "Second join committed"))
                .thenExecute(() -> f.b.command("hardrush ready"))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending() && P1LobbyRuntime.snapshot(f.server).orElseThrow()
                        .participants().get(f.b.profile.id()).ready(), "Second ready committed"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(!P1PlayerProfiles.isLocked(f.a.player) && !P1PlayerProfiles.isLocked(f.b.player), "Both competition profiles admitted"))
                .thenExecute(() -> {
                    for (var peer : List.of(f.a, f.b)) {
                        test.assertValueEqual(peer.player.gameMode.getGameModeForPlayer(), GameType.SURVIVAL, "Adventure never leaks into competition");
                        test.assertTrue(peer.player.getAbilities().mayBuild, "Competition restores survival building");
                    }
                    f.a.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    f.session.adapterFailure("大厅移动回归的故障隔离验证", new IllegalStateException("intentional test failure"));
                })
                .thenWaitUntil(() -> f.await(P1LobbyWorld.isLobby(f.a.player.level()) && P1PlayerProfiles.isLocked(f.a.player), "Failed round returns to the permanent lobby"))
                .thenExecute(() -> {
                    f.a.assertFrozen(); f.b.assertFrozen();
                    f.session.recheckStorage("fixture", "大厅回归核验", f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get() != null && f.reply.get().success(), "Storage recheck committed"))
                .thenExecute(() -> {
                    test.assertValueEqual(P1LobbyRuntime.snapshot(f.server).orElseThrow().phase(), RoundPhase.RECOVERY_REQUIRED, "Recheck keeps recovery isolation");
                    f.a.assertFrozen(); f.b.assertFrozen(); f.reply.set(null);
                    f.session.closeRecovery("fixture", "大厅回归结束恢复轮", f.reply::set);
                })
                .thenWaitUntil(() -> f.await(P1LobbyRuntime.snapshot(f.server).orElseThrow().sequence() == 2
                        && P1LobbyRuntime.snapshot(f.server).orElseThrow().phase() == RoundPhase.WAITING
                        && P1PlayerProfiles.canMoveInLobby(f.a.player) && P1PlayerProfiles.canMoveInLobby(f.b.player), "Rotation returns verified peers to the same lobby"))
                .thenExecute(() -> { f.assertLobby(); f.a.move(.2, 0, 30, 0, true); f.a.disconnect(); f.b.disconnect(); })
                .thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final AtomicReference<P1LobbySession.Reply> reply = new AtomicReference<>();
        final Peer a = new Peer("LobbyMovingA"), b = new Peer("LobbyMovingB");
        P1LobbySession session;
        P1PlayerProfiles profiles;
        String lastStage;
        int stageStarted;
        Fixture(GameTestHelper test) { this.test = test; server = test.getLevel().getServer(); }
        @SuppressWarnings("unchecked") <T> T service(String name) {
            try { var field = P1LobbyRuntime.class.getDeclaredField(name); field.setAccessible(true); return (T) ((Map<?, ?>) field.get(null)).get(server); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void bind() {
            session = service("SESSIONS"); profiles = service("PROFILES");
            try { var field = P1LobbySession.class.getDeclaredField("clock"); field.setAccessible(true); field.set(session, (MonotonicClock) clock::get); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void await(boolean condition, String stage) {
            if (!stage.equals(lastStage)) {
                lastStage = stage; stageStarted = server.getTickCount();
                System.out.println("Lobby movement stage: " + stage);
            }
            if (!condition && server.getTickCount() - stageStarted > 5000) throw new IllegalStateException("Lobby stage timed out: " + stage
                    + "; " + P1LobbyRuntime.status(server) + "; " + P1LobbyRuntime.snapshot(server).orElseThrow().participants());
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, stage + ": " + P1LobbyRuntime.status(server));
        }
        void assertLobby() {
            for (var peer : List.of(a, b)) {
                test.assertTrue(P1LobbyWorld.isLobby(peer.player.level()) && P1PlayerProfiles.canMoveInLobby(peer.player), "Healthy verified peers share the active lobby");
                test.assertTrue(P1PlayerProfiles.isLocked(peer.player), "Movement does not grant competition admission");
                test.assertValueEqual(peer.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "Every lobby peer is in adventure mode");
                test.assertFalse(peer.player.getAbilities().mayBuild || peer.player.getAbilities().mayfly, "Lobby abilities are authoritative");
            }
            test.assertTrue(a.player.level() == b.player.level(), "Both peers belong to the same native level");
        }
        void assertProtectionAndUi() {
            var floor = BlockPos.containing(a.player.position()).below(2);
            // The jump can leave feet above the ground; target the original flat floor directly.
            floor = new BlockPos(floor.getX(), P1LobbyWorld.open(server).holdingLevel().getRespawnData().pos().getY() - 1, floor.getZ());
            var original = a.player.level().getBlockState(floor);
            a.player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, floor, Direction.UP, 1));
            test.assertFalse(a.player.gameMode.destroyBlock(floor), "Native block breaking cannot bypass lobby protection");
            a.player.getInventory().setItem(0, new ItemStack(Items.STONE));
            a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false), 2));
            test.assertTrue(a.player.level().getBlockState(floor).equals(original) && a.player.level().getBlockState(floor.above()).isAir(), "Both block packets leave terrain unchanged");
            test.assertFalse(a.player.hurtServer(a.player.level(), a.player.damageSources().playerAttack(b.player), 5), "Lobby bodies cannot be damaged");
            a.player.getInventory().clearContent();
            a.command("hardrush menu"); test.assertTrue(P2LobbyUi.isMenu(a.player), "Adventure mode keeps the actual lobby menu available");
            a.player.connection.handleContainerClose(new ServerboundContainerClosePacket(a.player.containerMenu.containerId));
            var book = new BookPort.Book(UUID.randomUUID(), 1, 1, "大厅测试历史书", "HardRush", List.of("只读历史"));
            a.player.getInventory().setItem(0, P1BookPort.createItem(new OperationTag(book.roundId(), UUID.randomUUID(), 1), book));
            int before = a.connection.packets.size();
            a.player.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 3, 0, 0));
            test.assertTrue(a.connection.packets.subList(before, a.connection.packets.size()).stream().anyMatch(ClientboundOpenBookPacket.class::isInstance), "History book still opens through the actual use-item packet");
            a.player.getInventory().clearContent();
        }
        final class Peer {
            final GameProfile profile;
            PrepareSpawnTask prepare;
            RecordingConnection connection;
            EmbeddedChannel channel;
            ServerPlayer player;
            int acknowledged = Integer.MIN_VALUE;
            int batchCount;
            int observedFrom;
            Peer(String name) { profile = new GameProfile(UUID.randomUUID(), name); }
            void login(GameTestSequence sequence) {
                sequence.thenExecute(() -> { prepare = new PrepareSpawnTask(server, new NameAndId(profile)); prepare.start(packet -> {}); })
                        .thenWaitUntil(() -> await(prepare.tick(), "Native login data loaded"))
                        .thenExecute(() -> {
                            connection = new RecordingConnection(); channel = new EmbeddedChannel(connection); acknowledged = Integer.MIN_VALUE; batchCount = 0;
                            player = prepare.spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false)); prepare.close(); acknowledge();
                            test.assertTrue(P1PlayerProfiles.isLocked(player), "Native login begins in quarantine");
                            assertFrozen();
                        }).thenWaitUntil(() -> await(P1PlayerProfiles.canMoveInLobby(player), "Profile verification opens lobby movement"));
            }
            void assertFrozen() {
                acknowledge();
                var position = player.position();
                player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(position.add(.4, 0, 0), 40, 0, true, false));
                test.assertTrue(player.position().distanceToSqr(position) < .000001 && !P1PlayerProfiles.canMoveInLobby(player), "Pending or failed profiles cannot move");
                acknowledge();
            }
            void move(double x, double y, float yaw, float pitch, boolean ground) {
                acknowledge();
                var target = player.position().add(x, y, 0);
                player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(target, yaw, pitch, ground, false));
                player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
                test.assertTrue(player.position().distanceToSqr(target) < .000001, "Actual inbound movement reaches the server position: target="
                        + target + ", actual=" + player.position() + ", loaded=" + player.connection.hasClientLoaded()
                        + ", mobile=" + P1PlayerProfiles.canMoveInLobby(player));
            }
            boolean seesMovement(ServerPlayer other, boolean position, boolean rotation) {
                return connection.packets.subList(observedFrom, connection.packets.size()).stream().anyMatch(packet -> packet instanceof ClientboundMoveEntityPacket move
                            && move.getEntity(player.level()) == other && (!position || move.hasPosition()) && (!rotation || move.hasRotation())
                        || packet instanceof ClientboundEntityPositionSyncPacket sync && sync.id() == other.getId()
                            && (!position || sync.hasPosition()) && (!rotation || sync.hasRotation()));
            }
            void command(String command) {
                try { server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack()); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }
            void tickConnection() {
                if (player == null) return;
                acknowledge();
                // Embedded connections are not in the server's socket list. Run vanilla's
                // connection tick/doTick exactly as a real network connection would.
                player.connection.tick();
            }
            void acknowledge() {
                if (player == null) return;
                connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance).map(ClientboundPlayerPositionPacket.class::cast)
                        .reduce((first, last) -> last).filter(packet -> packet.id() != acknowledged).ifPresent(packet -> {
                            acknowledged = packet.id();
                            player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(packet.id(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
                            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                        });
                int batches = (int) connection.packets.stream().filter(ClientboundChunkBatchFinishedPacket.class::isInstance).count();
                while (batchCount < batches) { batchCount++; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64)); }
            }
            void disconnect() {
                connection.disconnect(Component.literal("大厅原生重连回归")); connection.handleDisconnection(); channel.finishAndReleaseAll(); player = null;
            }
        }
    }
    private static final class RecordingConnection extends Connection {
        final ArrayList<Packet<?>> packets = new ArrayList<>();
        RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        void record(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::record); else packets.add(packet);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
