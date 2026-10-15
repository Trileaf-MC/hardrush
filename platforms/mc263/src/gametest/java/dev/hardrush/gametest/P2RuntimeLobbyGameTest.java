package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.hunt.HuntVotingService;
import dev.hardrush.core.round.*;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
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
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Opt-in production runtime chain with two embedded native connections and real SQLite/profile IO. */
public final class P2RuntimeLobbyGameTest {
    private static final TicketType LOOT_FIXTURE = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    @GameTest(maxTicks = 60000)
    public void formalLobbyOutingsReturnDefeatAndOutsideReconnectKeepDurableIdentity(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.p2.testLobby")) { test.succeed(); return; }
        test.assertTrue(Boolean.getBoolean("hardrush.p1.enabled"), "The dedicated run requires the production runtime");
        var f = new Fixture(test);
        test.onEachTick(() -> {
            if (f.clock != null) f.clock.accumulateAndGet(System.nanoTime(), Math::max);
            f.monster.acknowledge(); f.target.acknowledge();
            test.assertFalse(P1LobbyRuntime.sessionState(f.server).filter(s -> s == P1LobbySession.State.FAILED).isPresent(),
                    "Production runtime failed: " + P1LobbyRuntime.status(f.server));
        });
        var sequence = test.startSequence()
                .thenWaitUntil(() -> f.await(P1LobbyRuntime.sessionState(f.server).filter(s -> s == P1LobbySession.State.OPEN).isPresent(), "Runtime bootstrap"))
                .thenExecute(f::installClock);
        f.monster.login(sequence);
        f.target.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && !f.session.mutationPending(), "World/profile barriers"))
                .thenExecute(() -> f.monster.command("hardrush join"))
                .thenWaitUntil(() -> f.await(f.monster.participant() != null && !f.session.mutationPending(), "First formal join"))
                .thenExecute(() -> f.target.command("hardrush join"))
                .thenWaitUntil(() -> f.await(f.target.participant() != null && !f.session.mutationPending(), "Second formal join"))
                .thenExecute(() -> f.monster.command("hardrush ready"))
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.COUNTDOWN && !f.session.mutationPending(), "Ready countdown"))
                .thenExecute(() -> f.target.command("hardrush ready"))
                .thenWaitUntil(() -> f.await(f.target.participant().ready() && !f.session.mutationPending(), "Second ready"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(f.humansReady(), "Both native human lives admitted"))
                .thenExecute(f::prepareArena)
                .thenExecute(() -> f.target.move(f.anchor))
                .thenWaitUntil(() -> f.await(f.target.participant().hasLeftProtection() && !f.session.mutationPending(), "Target protection exit committed"))
                .thenExecute(() -> f.target.move(f.anchor))
                .thenExecute(() -> f.monster.move(f.anchor.add(10, 0, 0)))
                .thenWaitUntil(() -> f.await(f.monster.participant().hasLeftProtection() && !f.session.mutationPending(), "Future monster protection exit committed"))
                .thenExecute(() -> f.monster.move(f.anchor.add(10, 0, 0)))
                .thenWaitUntil(() -> f.await(f.level.isPositionEntityTicking(f.monster.player.blockPosition()),
                        "Native loot chunk reaches entity ticking before the immediate human death query"))
                .thenExecute(() -> {
                    f.test.assertFalse(P2ProtectionRuntime.protectedEntity(f.target.player), "A real active human is outside protection");
                    f.monster.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    f.monster.player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, 6));
                    f.humanDrops = new AABB(BlockPos.containing(f.monster.player.position())).inflate(5);
                    f.killMonsterPeer(false);
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(true), "Native hardcore death enters the monster lobby"))
                .thenExecute(() -> {
                    f.monster.player = f.monster.current();
                    f.assertLobby(true);
                    f.test.assertTrue(f.monster.participant().monsterCooldownNanos() > 0, "First monster life has a deployment wait");
                    f.monster.openAndClick(Items.GUNPOWDER);
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.CREEPER && !f.session.mutationPending(), "Role choice durably saved"))
                .thenExecute(() -> f.monster.command("hardrush dimension overworld"))
                .thenWaitUntil(() -> f.await(!f.session.mutationPending(), "Dimension choice durably saved"))
                .thenExecute(() -> {
                    f.monster.command("hardrush deploy");
                    f.test.assertValueEqual(f.monster.participant().role(), ParticipantRole.MONSTER_LOBBY, "Early deploy cannot bypass the first 30 seconds");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    f.monster.openAndClick(Items.PLAYER_HEAD);
                    f.test.assertValueEqual(f.votes().ballot(f.monster.profile.id()).orElseThrow().targetId(), f.target.profile.id(), "Production vote records the chosen active human");
                    f.monster.command("hardrush deploy");
                })
                .thenWaitUntil(() -> f.await(f.deployed(1), "First formal safe deployment"))
                .thenExecute(() -> {
                    f.assertDeployment(1);
                    f.assertMenuDoesNotUnlockRoleDrops();
                    f.monster.command("hardrush return");
                    f.test.assertTrue(P2LobbyRuntime.channeling(f.monster.player), "Native return command begins a channel");
                    f.monster.move(f.monster.player.position().add(.2, 0, 0));
                })
                .thenWaitUntil(() -> f.await(!P2LobbyRuntime.channeling(f.monster.player), "Actual native movement cancels return"))
                .thenExecute(() -> {
                    f.monster.command("hardrush return");
                    f.ageReturnChannel();
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(false), "Completed return channel durably enters lobby"))
                .thenExecute(() -> {
                    f.assertLobby(false);
                    f.test.assertTrue(f.monster.participant().monsterCooldownNanos() > 0, "Voluntary return starts another 30-second wait");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    f.monster.command("hardrush deploy");
                })
                .thenWaitUntil(() -> f.await(f.deployed(2), "Second formal safe deployment"))
                .thenExecute(() -> { f.assertDeployment(2); f.killMonsterPeer(true); })
                .thenWaitUntil(() -> f.await(f.monsterReady(true), "Monster defeat uses native replacement and returns to lobby"))
                .thenExecute(() -> {
                    f.monster.player = f.monster.current();
                    f.assertLobby(true);
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    f.monster.command("hardrush deploy");
                })
                .thenWaitUntil(() -> f.await(f.deployed(3), "Third formal safe deployment"))
                .thenExecute(() -> { f.assertDeployment(3); f.monster.disconnect(); })
                .thenWaitUntil(() -> f.await(!f.monster.participant().online()
                        && f.monster.participant().role() == ParticipantRole.MONSTER_LOBBY && !f.session.mutationPending(), "Outside disconnect commits lobby identity"));
        f.monster.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.monsterReady(false) && f.monster.participant().online(), "Same UUID native reconnect remains a lobby monster"))
                .thenExecute(() -> {
                    f.assertLobby(false);
                    f.test.assertValueEqual(f.monster.participant().monsterDeploymentSequence(), 3, "Reconnect preserves outing sequence");
                    f.test.assertTrue(f.monster.participant().monsterCooldownNanos() > 0, "Outside reconnect retains its 30-second wait");
                    f.assertStored();
                    f.monster.disconnect(); f.target.disconnect();
                    f.level.getChunkSource().removeTicketWithRadius(LOOT_FIXTURE, f.lootChunk, 2);
                }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final Peer monster = new Peer("P2LobbyMonster"), target = new Peer("P2LobbyTarget");
        P1LobbySession session;
        P1PlayerProfiles profiles;
        AtomicLong clock;
        ServerLevel level;
        Vec3 anchor;
        ServerPlayer dead;
        AABB humanDrops;
        ChunkPos lootChunk;
        Fixture(GameTestHelper test) { this.test = test; server = test.getLevel().getServer(); }

        void installClock() {
            session = service("SESSIONS", P1LobbySession.class);
            profiles = service("PROFILES", P1PlayerProfiles.class);
            test.assertValueEqual(round().sequence(), 1L, "Dedicated storage starts at its first round");
            test.assertValueEqual(round().rules().mode(), GameMode.HARDCORE, "Actual runtime mode is hardcore");
            clock = new AtomicLong(System.nanoTime());
            try {
                var field = P1LobbySession.class.getDeclaredField("clock"); field.setAccessible(true);
                field.set(session, (MonotonicClock) clock::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        RoundSnapshot round() { return P1LobbyRuntime.snapshot(server).orElseThrow(); }
        void await(boolean condition, String message) {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + P1LobbyRuntime.status(server));
        }
        boolean humansReady() {
            return round().phase() == RoundPhase.RUNNING && !session.mutationPending()
                    && monster.participant().online() && target.participant().online()
                    && !P1PlayerProfiles.isLocked(monster.player) && !P1PlayerProfiles.isLocked(target.player);
        }
        void prepareArena() {
            var worlds = service("WORLDS", P1RoundWorldBundle.class);
            level = worlds.level(Level.OVERWORLD);
            BlockPos center = new BlockPos(worlds.spawn().getX() + 160, 220, worlds.spawn().getZ());
            anchor = Vec3.atBottomCenterOf(center);
            lootChunk = new ChunkPos((center.getX() + 10) >> 4, center.getZ() >> 4);
            level.getChunkSource().addTicketWithRadius(LOOT_FIXTURE, lootChunk, 2);
            // Fixture setup alone loads chunks and creates terrain. The production search cannot do either.
            for (int x = -67; x <= 67; x++) for (int z = -67; z <= 67; z++) {
                BlockPos feet = center.offset(x, 0, z);
                level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 3; y++) level.setBlock(feet.above(y), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(feet.above(3), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        void killMonsterPeer(boolean alreadyMonster) {
            dead = monster.player;
            if (!alreadyMonster) test.assertTrue(level.isPositionEntityTicking(dead.blockPosition()),
                    "Immediate death-drop assertion requires the native loot arena to remain entity ticking");
            test.assertTrue(dead.hurtServer(level, dead.damageSources().genericKill(), Float.MAX_VALUE), "Native damage outside protection kills this body");
            test.assertTrue(dead.isDeadOrDying() && P1PlayerProfiles.isLocked(dead), "Production death hook immediately holds the dead body");
            if (!alreadyMonster) test.assertValueEqual(diamonds(), 3, "Native human death drops the original inventory exactly once");
            dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            test.assertTrue(monster.current() == dead && dead.connection.player == dead && dead.isDeadOrDying(), "Early native respawn cannot bypass SQLite identity commit");
        }
        boolean monsterReady(boolean replacementRequired) {
            var current = monster.current();
            return monster.participant().role() == ParticipantRole.MONSTER_LOBBY && current != null
                    && (!replacementRequired || current != dead) && current.isAlive() && current.level() == server.overworld()
                    && profiles.isReady(monster.profile.id(), round()) && !session.mutationPending();
        }
        boolean deployed(int sequence) {
            var p = monster.participant();
            return p.role() == ParticipantRole.MONSTER_DEPLOYED && p.monsterDeploymentSequence() == sequence
                    && monster.player.level() == level && !P1PlayerProfiles.isLocked(monster.player)
                    && MonsterRoles.isMonster(monster.player) && !session.mutationPending();
        }
        void assertDeployment(int sequence) {
            test.assertValueEqual(monster.participant().monsterDeploymentSequence(), sequence, "Exactly one persisted sortie is admitted");
            double horizontal = monster.player.position().subtract(target.player.position()).horizontalDistance();
            test.assertTrue(horizontal >= 32 && horizontal <= 64, "Actual native monster spawns in the target's 32-64 block ring");
            test.assertTrue(monster.player.position().distanceToSqr(target.player.position()) >= 24 * 24, "Actual body respects the human separation");
            test.assertFalse(P2ProtectionRuntime.protectedEntity(monster.player), "Actual monster never enters spawn protection");
            test.assertValueEqual(MonsterRoles.nativeModel(monster.player).getType(), net.minecraft.world.entity.EntityTypes.CREEPER, "Persisted creeper selection drives the real native role");
            test.assertTrue(monster.player.getInventory().getItem(0).is(Items.GUNPOWDER), "Only the creeper role control is equipped");
            test.assertValueEqual(diamonds(), 3, "Outings do not duplicate or pick up the old human inventory");
        }
        void assertMenuDoesNotUnlockRoleDrops() {
            monster.command("hardrush menu");
            test.assertTrue(dev.hardrush.p2.lobby.P2LobbyUi.isMenu(monster.player), "Outside monster can open the return menu");
            var bounds = monster.player.getBoundingBox().inflate(5);
            int before = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.GUNPOWDER)).mapToInt(ItemStack::getCount).sum();
            monster.player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, monster.player.blockPosition().below(), Direction.UP, 0));
            int after = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.GUNPOWDER)).mapToInt(ItemStack::getCount).sum();
            test.assertValueEqual(after, before, "Lobby GUI click exemption never unlocks raw fixed-role drop packets");
            test.assertTrue(monster.player.getInventory().getItem(0).is(Items.GUNPOWDER), "The fixed creeper control remains equipped while the menu is open");
            monster.player.closeContainer();
        }
        void assertLobby(boolean replaced) {
            monster.player = monster.current();
            test.assertTrue(monster.player != null && monster.player.isAlive() && monster.player.level() == server.overworld()
                    && P1PlayerProfiles.isLocked(monster.player), "The authoritative native body is isolated in the real lobby");
            if (replaced) test.assertTrue(monster.player != dead, "Defeat must create a native replacement rather than heal the dead object");
            test.assertTrue(monster.player.connection.player == monster.player, "The native connection rebinds to the live lobby body");
            test.assertTrue(monster.player.getInventory().isEmpty(), "No role controls or human death inventory enter the lobby");
            test.assertFalse(MonsterRoles.isMonster(monster.player), "Lobby has no active prototype role body");
            test.assertValueEqual(monster.player.getEnderChestInventory().getItem(0).getCount(), 6, "Same-round ender storage survives every sortie and native respawn");
            test.assertValueEqual(monster.participant().lifeSequence(), 1, "Monster outings never create a new human life");
            test.assertValueEqual(diamonds(), 3, "Exactly one human death drop set remains");
        }
        void ageReturnChannel() {
            try {
                Object binding = binding();
                var channelsField = binding.getClass().getDeclaredField("channels"); channelsField.setAccessible(true);
                @SuppressWarnings("unchecked") var channels = (Map<UUID, Object>) channelsField.get(binding);
                Object old = channels.get(monster.profile.id());
                test.assertTrue(old != null, "The production command created a channel before fixture clock adjustment");
                var constructor = old.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
                var p = monster.participant();
                channels.put(monster.profile.id(), constructor.newInstance(monster.player, monster.player.level(), monster.player.position(),
                        System.nanoTime() - TimeUnit.SECONDS.toNanos(6), p.lifeSequence(), p.monsterDeploymentSequence()));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        Object binding() throws ReflectiveOperationException {
            var field = P2LobbyRuntime.class.getDeclaredField("BINDINGS"); field.setAccessible(true);
            return ((Map<?, ?>) field.get(null)).get(server);
        }
        HuntVotingService votes() {
            try {
                Object binding = binding();
                var field = binding.getClass().getDeclaredField("votes"); field.setAccessible(true);
                return (HuntVotingService) field.get(binding);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        int diamonds() {
            return level.getEntitiesOfClass(ItemEntity.class, humanDrops).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
        }
        void assertStored() {
            try (var store = new SqliteRoundStore(server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"))) {
                var saved = RoundSnapshotCodec.decode(store.load(round().roundId()).orElseThrow().payload()).participants().get(monster.profile.id());
                test.assertValueEqual(saved.role(), ParticipantRole.MONSTER_LOBBY, "Real SQLite stores the returned lobby identity");
                test.assertValueEqual(saved.monsterRole(), MonsterRole.CREEPER, "Real SQLite retains role choice across reconnect");
                test.assertValueEqual(saved.monsterDimension(), MonsterDimension.OVERWORLD, "Real SQLite retains dimension choice across reconnect");
                test.assertValueEqual(saved.monsterDeploymentSequence(), 3, "Real SQLite retains sortie label across reconnect");
                test.assertValueEqual(saved.lifeSequence(), 1, "Real SQLite never revives the human through a monster respawn");
                var record = store.participantRecord(round().roundId(), monster.profile.id()).orElseThrow();
                test.assertValueEqual(record.revivalCount(), 0, "Neither defeat nor reconnect consumes/grants a human revival");
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        <T> T service(String name, Class<T> type) {
            try {
                var field = P1LobbyRuntime.class.getDeclaredField(name); field.setAccessible(true);
                return type.cast(((Map<?, ?>) field.get(null)).get(server));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }

        final class Peer {
            final GameProfile profile;
            PrepareSpawnTask prepare;
            RecordingConnection connection;
            EmbeddedChannel channel;
            ServerPlayer player;
            int acknowledged = Integer.MIN_VALUE;
            Peer(String name) { profile = new GameProfile(UUID.randomUUID(), name); }
            Participant participant() { return round().participants().get(profile.id()); }
            ServerPlayer current() { return server.getPlayerList().getPlayer(profile.id()); }
            void login(GameTestSequence sequence) {
                sequence.thenExecute(() -> { prepare = new PrepareSpawnTask(server, new NameAndId(profile)); prepare.start(packet -> {}); })
                        .thenWaitUntil(() -> await(prepare.tick(), "Native UUID player-data load: " + profile.name()))
                        .thenExecute(() -> {
                            connection = new RecordingConnection(); channel = new EmbeddedChannel(connection); acknowledged = Integer.MIN_VALUE;
                            player = prepare.spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false)); prepare.close(); acknowledge();
                            test.assertTrue(player.level() == server.overworld() && P1PlayerProfiles.isLocked(player), "Native login starts in real profile quarantine");
                        }).thenWaitUntil(() -> await(profiles.isReady(profile.id(), round()), "Real profile journal ready: " + profile.name()));
            }
            void command(String command) {
                try { server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack()); }
                catch (Exception error) { throw new IllegalStateException(command, error); }
            }
            void openAndClick(Item icon) {
                command("hardrush menu");
                test.assertTrue(dev.hardrush.p2.lobby.P2LobbyUi.isMenu(player), "The actual isolated native connection opens the lobby GUI");
                var menu = player.containerMenu;
                for (short slot = 0; slot < 45; slot++) if (menu.getSlot(slot).getItem().is(icon)) {
                    player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                            slot, (byte) 0, ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
                    test.assertTrue(player.getInventory().isEmpty() && menu.getCarried().isEmpty(), "The real container packet triggers an action without moving a GUI icon");
                    return;
                }
                throw new IllegalStateException("Expected lobby icon is absent: " + icon);
            }
            void move(Vec3 position) { player.teleport(new TeleportTransition(level, position, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING)); acknowledge(); }
            void acknowledge() {
                if (connection == null) return;
                var nativePlayer = current(); if (nativePlayer == null) return;
                connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance).map(ClientboundPlayerPositionPacket.class::cast)
                        .reduce((previous, latest) -> latest).filter(packet -> packet.id() != acknowledged).ifPresent(packet -> {
                            acknowledged = packet.id();
                            nativePlayer.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(packet.id(), nativePlayer.getX(), nativePlayer.getY(), nativePlayer.getZ(), nativePlayer.getYRot(), nativePlayer.getXRot()));
                            nativePlayer.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                        });
            }
            void disconnect() {
                connection.disconnect(Component.literal("P2 lobby native reconnect")); connection.handleDisconnection();
                test.assertTrue(current() == null && !profiles.manages(player), "Native disconnect releases both PlayerList and the profile binding");
                channel.finishAndReleaseAll(); player = null;
            }
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
