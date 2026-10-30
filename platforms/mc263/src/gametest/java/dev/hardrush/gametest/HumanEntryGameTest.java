package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.api.OperationTag;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.book.WrittenBookPort;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.lobby.LobbyMenus;
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

/** Native production position/lectern/menu/death flow in one isolated server, no graphical client. */
public final class HumanEntryGameTest {
    @GameTest(maxTicks = 60000)
    public void campAssemblyCancellationDeathAndMiddleGate(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.HUMAN_ENTRY)) { test.succeed(); return; }
        var f = new Fixture(test);
        test.onEachTick(() -> { f.drain(); f.a.tickConnection(); f.b.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready()
                && RoundRuntime.snapshot(f.server).filter(r -> r.phase() == RoundPhase.WAITING).isPresent()
                && !((RoundSession) f.service("SESSIONS")).mutationPending(), "Camp ready"))
                .thenExecute(() -> { f.bind(); f.callbacks(); });
        f.a.login(sequence); f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && f.idle(), "World ready"))
                .thenExecute(() -> {
                    var lobby = LobbyWorld.open(f.server); var anchor = BlockPos.containing(lobby.holdingSpawn());
                    test.assertTrue(lobby.loadedChunks() > 9 && lobby.layoutVersion() == 1, "Full camp footprint replaces 3x3 readiness");
                    for (var pos : dev.hardrush.world.CampBlueprint.blocks(anchor).keySet())
                        test.assertTrue(lobby.holdingLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null, "Every owned block is loaded");
                    for (var facility : dev.hardrush.core.lobby.LobbyLayout.Facility.values()) {
                        var pos = BlockPos.containing(lobby.layoutPosition(dev.hardrush.core.lobby.LobbyLayout.facility(facility)));
                        test.assertTrue(lobby.holdingLevel().getBlockState(pos).equals(dev.hardrush.world.CampBlueprint.facilityBlock(facility)),
                                "Facility physically built: " + facility);
                    }
                    for (var landing : dev.hardrush.core.lobby.LobbyLayout.Landing.values()) test.assertTrue(lobby.safeLanding(landing), "Safe landing: " + landing);
                    f.assertRoleModels();
                    f.assertLobby(); f.profileReady(f.b, false); f.hold = true; f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.session.mutationPending(), "Assembly pending SQLite callback"))
                .thenExecute(() -> f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.WAIT_OUTSIDE))
                .thenIdle(3)
                .thenExecute(() -> { f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY); f.hold = false; })
                .thenWaitUntil(() -> f.await(f.cancelSeen && f.ready(f.a) && f.idle(), "Fresh re-entry after cancelled old intention"))
                .thenExecute(() -> { test.assertValueEqual(f.round().remainingPhaseMillis(), 30000L, "Full new countdown");
                    test.assertFalse(PlayerProfiles.canMoveInLobby(f.b.player), "One pending profile stays individually frozen");
                    test.assertTrue(f.ready(f.a), "Another eligible UUID can prepare independently"); f.profileReady(f.b, true); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(7)); })
                .thenWaitUntil(() -> f.await(f.idle(), "Seven second checkpoint"))
                .thenExecute(() -> f.place(f.b, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.b) && f.idle(), "Later player joins remaining countdown"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.round().remainingPhaseMillis(), 23000L, "Later join does not reset");
                    f.a.move(.1, .42, 90, 15, false); f.a.player.connection.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false,false,false,false,true,false,false)));
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(22)); f.place(f.b, dev.hardrush.core.lobby.LobbyLayout.Landing.WAIT_OUTSIDE);
                })
                .thenWaitUntil(() -> f.await(!f.ready(f.b) && f.idle(), "Second leaves in final second"))
                .thenExecute(() -> { test.assertTrue(f.ready(f.a), "Jump and turn keep preparation"); f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.WAIT_OUTSIDE); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(2)); })
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && f.idle(), "Last-second exit cancels even at zero"))
                .thenExecute(() -> f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY))
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "Single human prepares"))
                .thenExecute(() -> {
                    f.oldRoleModels = LobbyInteractions.get(f.server).orElseThrow().roleModels().view(f.a.player);
                    f.a.disconnect();
                });
        f.a.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && !f.ready(f.a) && f.idle(), "Ordinary disconnect cancelled readiness"))
                .thenExecute(() -> {
                    LobbyInteractions.tick(f.server);
                    var router = LobbyInteractions.get(f.server).orElseThrow();
                    var ids = router.roleModels().view(f.a.player);
                    test.assertValueEqual(ids.size(), 4, "Reconnect restores the three native monster models and the witch");
                    test.assertTrue(ids.values().stream().noneMatch(f.oldRoleModels.values()::contains), "Reconnect never reuses stale role IDs");
                    HuntInputFixtures.click(f.a.player, f.oldRoleModels.get(LobbyLayout.Facility.ZOMBIE), InteractionHand.MAIN_HAND);
                    test.assertValueEqual(router.lastResult(f.a.player).orElseThrow(), LobbyInteractions.Result.UNREGISTERED, "Old connection role ID is rejected");
                    test.assertFalse(LobbyWorld.open(f.server).inAssembly(f.a.player, true), "Reconnect stays outside");
                    f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.idle(), "Single human prepares again"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && !PlayerProfiles.isLocked(f.a.player) && f.idle(), "One human starts at natural spawn"))
                .thenExecute(() -> {
                    test.assertTrue(PlayerProfiles.isLocked(f.b.player), "Outside visitor was not admitted");
                    var worlds = (dev.hardrush.world.RoundWorldBundle) f.service("WORLDS");
                    test.assertTrue(f.a.player.level() == worlds.level(net.minecraft.world.level.Level.OVERWORLD), "Natural round overworld admission");
                    f.a.player.setRespawnPosition(new ServerPlayer.RespawnConfig(net.minecraft.world.level.storage.LevelData.RespawnData.of(f.a.player.level().dimension(), worlds.spawn(), 0, 0), true), false);
                    f.a.player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                    f.a.player.hurtServer(f.a.player.level(), f.a.player.damageSources().genericKill(), Float.MAX_VALUE);
                })
                .thenWaitUntil(() -> f.await(f.round().participants().get(f.a.profile.id()).role() == dev.hardrush.core.round.ParticipantRole.HUMAN_RESPAWNING && f.idle(), "Ordinary death persists"))
                .thenExecute(() -> { f.a.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)); f.a.player = f.a.player.connection.player; f.a.acknowledge(); })
                .thenWaitUntil(() -> f.await(!PlayerProfiles.isLocked(f.a.player) && f.round().participants().get(f.a.profile.id()).lifeSequence() == 2 && f.idle(), "Native respawn commits exactly one next life"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.a.player.getEnderChestInventory().getItem(0).getCount(), 3, "Ordinary death preserves ender storage");
                    test.assertTrue(f.a.player.getRespawnConfig() != null, "Ordinary death preserves respawn binding");
                });
        f.nativeRespawn(sequence, f::bed, 3, () -> {
            test.assertTrue(f.a.player.level() == f.bindingLevel && f.a.player.position().distanceTo(Vec3.atBottomCenterOf(f.binding)) < 4, "Valid native bed wins over natural spawn");
        });
        f.nativeRespawn(sequence, f::anchor, 4, () -> {
            test.assertTrue(f.a.player.level() == f.bindingLevel && f.a.player.position().distanceTo(Vec3.atBottomCenterOf(f.binding)) < 4, "Valid Nether anchor preserves dimension");
            test.assertValueEqual(f.bindingLevel.getBlockState(f.binding).getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE), 3, "Vanilla consumes one anchor charge");
        });
        f.nativeRespawn(sequence, () -> f.bindingLevel.setBlockAndUpdate(f.binding, Blocks.AIR.defaultBlockState()), 5, () -> {
            var worlds = (dev.hardrush.world.RoundWorldBundle) f.service("WORLDS");
            test.assertTrue(f.a.player.level() == worlds.level(net.minecraft.world.level.Level.OVERWORLD)
                    && f.a.player.position().distanceTo(Vec3.atBottomCenterOf(worlds.spawn())) < 4, "Invalid anchor falls back to this round's natural spawn");
        });
        sequence.thenExecute(() -> {
                    f.a.player.getInventory().setItem(4, new ItemStack(Items.GOLD_INGOT, 7));
                    f.savedPosition = f.a.player.position(); f.savedLevel = f.a.player.level(); f.a.disconnect();
                });
        f.a.login(sequence, true);
        sequence.thenExecute(() -> {
                    test.assertTrue(f.a.player.level() == f.savedLevel && f.a.player.position().distanceTo(f.savedPosition) < .1, "Living reconnect restores saved native location");
                    test.assertValueEqual(f.a.player.getInventory().getItem(4).getCount(), 7, "Living reconnect preserves inventory");
                    test.assertValueEqual(f.round().participants().get(f.a.profile.id()).lifeSequence(), 5, "Reconnect never creates another life");
                    f.gate(f.b); f.b.player.connection.handleContainerClose(new ServerboundContainerClosePacket(f.b.player.containerMenu.containerId));
                    test.assertValueEqual(f.round().participants().get(f.b.profile.id()).lifeSequence(), 0, "Close before confirmation abandons admission");
                    f.gate(f.b); f.hold = true; f.click(f.b, (short)2);
                    test.assertTrue(f.session.mutationPending(), "Confirmation accepted once");
                    f.click(f.b, (short)2); f.b.player.connection.handleContainerClose(new ServerboundContainerClosePacket(f.b.player.containerMenu.containerId)); f.hold = false;
                })
                .thenWaitUntil(() -> f.await(!PlayerProfiles.isLocked(f.b.player) && f.idle(), "Accepted conversion survives close and duplicate clicks"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.round().participants().get(f.b.profile.id()).lifeSequence(), 1, "One middle admission");
                    test.assertTrue(f.round().participants().values().stream().allMatch(p -> p.role() == dev.hardrush.core.round.ParticipantRole.HUMAN_ACTIVE), "Both are active ordinary humans");
                    f.reply.set(null); f.session.updateNextMode(dev.hardrush.core.config.GameMode.HARDCORE, "fixture", f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get() != null && f.reply.get().success() && f.idle(), "Next mode persisted for isolated hardcore refusal test"))
                .thenExecute(() -> { f.reply.set(null); f.session.adminAbort("fixture", "结束普通模式入场测试，验证极限死者拒绝", f.reply::set); })
                .thenWaitUntil(() -> f.await(f.round().sequence() == 2 && f.round().phase() == RoundPhase.WAITING && f.idle()
                        && PlayerProfiles.canMoveInLobby(f.a.player) && PlayerProfiles.canMoveInLobby(f.b.player), "Hardcore round prepared independently"))
                .thenExecute(() -> { f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY); f.place(f.b, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY); })
                .thenWaitUntil(() -> f.await(f.ready(f.a) && f.ready(f.b) && f.idle(), "Two hardcore humans prepared"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(() -> f.await(!PlayerProfiles.isLocked(f.a.player) && !PlayerProfiles.isLocked(f.b.player) && f.idle(), "Hardcore natural admissions"))
                .thenExecute(() -> f.a.player.hurtServer(f.a.player.level(), f.a.player.damageSources().genericKill(), Float.MAX_VALUE))
                .thenWaitUntil(() -> {
                    f.a.player = f.server.getPlayerList().getPlayer(f.a.profile.id());
                    f.await(f.idle() && f.round().participants().get(f.a.profile.id()).role() == dev.hardrush.core.round.ParticipantRole.MONSTER_LOBBY
                            && PlayerProfiles.canMoveInLobby(f.a.player), "Hardcore death enters monster camp");
                })
                .thenExecute(() -> {
                    test.assertTrue(f.a.player.position().distanceTo(LobbyWorld.open(f.server).landing(dev.hardrush.core.lobby.LobbyLayout.Landing.MONSTER)) < .1, "Monster purpose landing is used");
                    f.place(f.a, dev.hardrush.core.lobby.LobbyLayout.Landing.RESTORE_READY);
                })
                .thenWaitUntil(() -> f.await(!LobbyWorld.open(f.server).inAssembly(f.a.player, true), "Dead UUID ejected from assembly"))
                .thenExecute(() -> {
                    f.gate(f.a, false);
                    test.assertValueEqual(f.round().participants().get(f.a.profile.id()).role(), dev.hardrush.core.round.ParticipantRole.MONSTER_LOBBY, "Gate and assembly cannot restore a dead UUID");
                    f.a.disconnect(); f.b.disconnect();
                    f.reopenLegacyCamp("PREPARED", false);
                })
                .thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready(), "Interrupted v1 camp upgrades"))
                .thenExecute(() -> { f.assertUpgradedCamp(); f.reopenLegacyCamp("COMPLETE", true); })
                .thenWaitUntil(() -> f.await(LobbyWorld.open(f.server).ready(), "Completed v1 camp upgrades"))
                .thenExecute(() -> {
                    f.assertUpgradedCamp();
                    var lobby = LobbyWorld.open(f.server);
                    test.assertTrue(lobby.holdingLevel().getBlockState(BlockPos.containing(lobby.holdingSpawn()).offset(-8, -1, 10)).is(Blocks.GOLD_BLOCK),
                            "Completed camp upgrade preserves unrelated administrator terrain");
                }).thenSucceed();
    }

    static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final AtomicReference<RoundSession.Reply> reply = new AtomicReference<>();
        final Peer a = new Peer("CampHumanA"), b = new Peer("CampHumanB");
        RoundSession session;
        PlayerProfiles profiles;
        Vec3 savedPosition;
        net.minecraft.server.level.ServerLevel savedLevel;
        String lastStage;
        int stageStarted;
        Map<LobbyLayout.Facility, Integer> oldRoleModels;
        Fixture(GameTestHelper test) { this.test = test; server = test.getLevel().getServer(); }
        @SuppressWarnings("unchecked") <T> T service(String name) {
            try { var field = RoundRuntime.class.getDeclaredField(name); field.setAccessible(true); return (T) ((Map<?, ?>) field.get(null)).get(server); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void bind() {
            session = service("SESSIONS"); profiles = service("PROFILES");
            clock.set(System.nanoTime());
            try {
                var last = RoundSession.class.getDeclaredField("lastCheckpointNanos"); last.setAccessible(true); last.set(session, clock.get());
                var ef = RoundSession.class.getDeclaredField("engine"); ef.setAccessible(true); var engine = ef.get(session);
                var boundary = dev.hardrush.core.round.RoundEngine.class.getDeclaredField("boundary"); boundary.setAccessible(true); boundary.set(engine, clock.get());
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            try { var field = RoundSession.class.getDeclaredField("clock"); field.setAccessible(true); field.set(session, (MonotonicClock) clock::get); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void await(boolean condition, String stage) {
            if (!stage.equals(lastStage)) {
                lastStage = stage; stageStarted = server.getTickCount();
                System.out.println("Lobby movement stage: " + stage);
            }
            if (!condition && server.getTickCount() - stageStarted > 5000) throw new IllegalStateException("Lobby stage timed out: " + stage
                    + "; " + RoundRuntime.status(server) + "; " + RoundRuntime.snapshot(server).orElseThrow().participants());
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, stage + ": " + RoundRuntime.status(server));
        }
        dev.hardrush.core.round.RoundSnapshot round() { return session.snapshot().orElseThrow(); }
        boolean idle() { return !session.mutationPending(); }
        boolean ready(Peer peer) { var p = round().participants().get(peer.profile.id()); return p != null && p.ready(); }
        void place(Peer peer, dev.hardrush.core.lobby.LobbyLayout.Landing landing) { profiles.placeInLobby(peer.player, landing); peer.acknowledge(); }
        final java.util.concurrent.LinkedBlockingQueue<Runnable> held = new java.util.concurrent.LinkedBlockingQueue<>();
        boolean hold, cancelSeen;
        void callbacks() {
            try {
                var listener = RoundSession.class.getDeclaredField("committedListener"); listener.setAccessible(true);
                @SuppressWarnings("unchecked") var original = (java.util.function.BiConsumer<dev.hardrush.core.round.RoundSnapshot, dev.hardrush.core.round.RoundSnapshot>) listener.get(session);
                session.onCommitted((before, after) -> {
                    original.accept(before, after);
                    var old = before.participants().get(a.profile.id()); var next = after.participants().get(a.profile.id());
                    if (old != null && next != null && old.ready() && !next.ready()) cancelSeen = true;
                });
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            try { var field = RoundSession.class.getDeclaredField("callbacks"); field.setAccessible(true); field.set(session, (java.util.concurrent.Executor) held::add); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void drain() { if (!hold) { Runnable task; while ((task = held.poll()) != null) task.run(); } }
        BlockPos binding;
        net.minecraft.server.level.ServerLevel bindingLevel;
        // Controlled bed/anchor terrain exists only in this test artifact, never in camp production.
        void clearRespawnArea(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
            level.getChunk(pos);
            for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
                level.setBlockAndUpdate(pos.offset(x,-1,z), Blocks.OBSIDIAN.defaultBlockState());
                for(int y=0;y<3;y++) level.setBlockAndUpdate(pos.offset(x,y,z), Blocks.AIR.defaultBlockState());
            }
        }
        void bed() {
            var worlds = (dev.hardrush.world.RoundWorldBundle) service("WORLDS");
            bindingLevel = worlds.level(net.minecraft.world.level.Level.OVERWORLD); binding = worlds.spawn().offset(8,10,0);
            clearRespawnArea(bindingLevel, binding);
            var bed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.EAST);
            bindingLevel.setBlock(binding.west(), bed.setValue(net.minecraft.world.level.block.AbstractBedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
            bindingLevel.setBlock(binding, bed.setValue(net.minecraft.world.level.block.AbstractBedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
            a.player.setRespawnPosition(new ServerPlayer.RespawnConfig(net.minecraft.world.level.storage.LevelData.RespawnData.of(bindingLevel.dimension(), binding, 0, 0), false), false);
        }
        void anchor() {
            var worlds = (dev.hardrush.world.RoundWorldBundle) service("WORLDS");
            bindingLevel = worlds.level(net.minecraft.world.level.Level.NETHER); binding = new BlockPos(0,60,0); clearRespawnArea(bindingLevel,binding);
            bindingLevel.setBlockAndUpdate(binding, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE, 4));
            a.player.setRespawnPosition(new ServerPlayer.RespawnConfig(net.minecraft.world.level.storage.LevelData.RespawnData.of(bindingLevel.dimension(), binding, 0, 0), false), false);
        }
        void nativeRespawn(GameTestSequence sequence, Runnable setup, int life, Runnable assertion) {
            sequence.thenExecute(() -> { setup.run(); a.player.hurtServer(a.player.level(), a.player.damageSources().genericKill(), Float.MAX_VALUE); })
                    .thenWaitUntil(() -> await(idle() && round().participants().get(a.profile.id()).role() == dev.hardrush.core.round.ParticipantRole.HUMAN_RESPAWNING, "Native death for life " + life))
                    .thenExecute(() -> { a.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)); a.player = a.player.connection.player; a.acknowledge(); })
                    .thenWaitUntil(() -> await(idle() && !PlayerProfiles.isLocked(a.player) && round().participants().get(a.profile.id()).lifeSequence() == life, "Native respawn life " + life))
                    .thenExecute(assertion);
        }
        void profileReady(Peer peer, boolean ready) {
            // Controlled per-UUID pending adapter, not a simulated disk failure.
            try {
                var field = PlayerProfiles.class.getDeclaredField("players"); field.setAccessible(true);
                var entry = ((Map<?, ?>)field.get(profiles)).get(peer.profile.id());
                var state = entry.getClass().getDeclaredField("ready"); state.setAccessible(true); state.set(entry, ready);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void gate(Peer peer) { gate(peer, true); }
        void gate(Peer peer, boolean expected) {
            var lobby = LobbyWorld.open(server); var pos = lobby.layoutPosition(dev.hardrush.core.lobby.LobbyLayout.facility(dev.hardrush.core.lobby.LobbyLayout.Facility.MIDROUND));
            peer.player.teleportTo(lobby.holdingLevel(), pos.x, pos.y, pos.z + 2, java.util.Set.of(), 180, 0, true); peer.acknowledge();
            var block = BlockPos.containing(pos);
            peer.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(block), Direction.UP, block, false), 10));
            test.assertValueEqual(LobbyMenus.isMenu(peer.player), expected, "Registered middle gate validates current identity");
        }
        void click(Peer peer, short slot) {
            var menu = peer.player.containerMenu;
            peer.player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), slot, (byte)0,
                net.minecraft.world.inventory.ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));
        }
        void assertLobby() {
            for (var peer : List.of(a, b)) {
                test.assertTrue(LobbyWorld.isLobby(peer.player.level()) && PlayerProfiles.canMoveInLobby(peer.player), "Healthy verified peers share the active lobby");
                test.assertTrue(PlayerProfiles.isLocked(peer.player), "Movement does not grant competition admission");
                test.assertValueEqual(peer.player.gameMode.getGameModeForPlayer(), GameType.ADVENTURE, "Every lobby peer is in adventure mode");
                test.assertFalse(peer.player.getAbilities().mayBuild || peer.player.getAbilities().mayfly, "Lobby abilities are authoritative");
            }
            test.assertTrue(a.player.level() == b.player.level(), "Both peers belong to the same native level");
        }
        void assertRoleModels() {
            LobbyInteractions.tick(server);
            var router = LobbyInteractions.get(server).orElseThrow();
            var ids = router.roleModels().view(a.player);
            test.assertValueEqual(ids.size(), 4, "Waiting humans see all three monster choices and the witch merchant");
            test.assertTrue(ids.values().stream().noneMatch(router.roleModels().view(b.player).values()::contains), "Each connection owns independent role IDs");
            var lobby = LobbyWorld.open(server);
            for (var facility : LobbyLayout.MONSTER_FACILITIES) {
                int id = ids.get(facility);
                var spawn = a.connection.packets.stream().filter(ClientboundAddEntityPacket.class::isInstance)
                        .map(ClientboundAddEntityPacket.class::cast).filter(p -> p.getId() == id).findFirst().orElseThrow();
                var type = switch (facility) {
                    case ZOMBIE -> net.minecraft.world.entity.EntityTypes.ZOMBIE;
                    case SKELETON -> net.minecraft.world.entity.EntityTypes.SKELETON;
                    case CREEPER -> net.minecraft.world.entity.EntityTypes.CREEPER;
                    default -> throw new AssertionError("Not a monster facility");
                };
                test.assertTrue(spawn.getType() == type, "Uses the native full monster model: " + facility);
                var pos = lobby.layoutPosition(LobbyLayout.facility(facility));
                test.assertTrue(pos.distanceToSqr(new Vec3(spawn.getX(), spawn.getY(), spawn.getZ())) < .000001, "Model occupies the former lectern position");
                test.assertTrue(a.player.level().getEntity(id) == null, "Display is never a live hostile mob");
                test.assertTrue(a.connection.packets.stream().anyMatch(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == id), "Native model metadata is sent");
            }
            int witch = ids.get(LobbyLayout.Facility.MERCHANT);
            test.assertTrue(a.connection.packets.stream().filter(ClientboundAddEntityPacket.class::isInstance)
                    .map(ClientboundAddEntityPacket.class::cast).anyMatch(p -> p.getId() == witch
                            && p.getType() == net.minecraft.world.entity.EntityTypes.WITCH), "The merchant is a native witch model");
            test.assertTrue(a.player.level().getEntity(witch) == null, "The witch is never a live mob");
            test.assertTrue(a.connection.packets.stream().anyMatch(p -> p instanceof ClientboundSetEquipmentPacket equipment
                    && equipment.getEntity() == ids.get(LobbyLayout.Facility.SKELETON)
                    && equipment.getSlots().stream().anyMatch(slot -> slot.getFirst() == net.minecraft.world.entity.EquipmentSlot.MAINHAND
                    && slot.getSecond().is(Items.BOW))), "Skeleton carries its native bow");
            int id = ids.get(LobbyLayout.Facility.ZOMBIE);
            HuntInputFixtures.click(a.player, id, InteractionHand.MAIN_HAND);
            test.assertValueEqual(router.lastResult(a.player).orElseThrow(), LobbyInteractions.Result.DISTANCE, "Remote role click is rejected");
            var pos = lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.ZOMBIE));
            HuntInputFixtures.move(a.player, pos.add(0, 0, 2)); a.acknowledge();
            HuntInputFixtures.click(a.player, id, InteractionHand.OFF_HAND);
            test.assertValueEqual(router.lastResult(a.player).orElseThrow(), LobbyInteractions.Result.INPUT, "Offhand role duplicate is rejected");
            var forged = new ServerboundInteractPacket(id, InteractionHand.MAIN_HAND, new Vec3(Double.NaN, 1, 0), false);
            test.assertValueEqual(LobbyInteractions.entity(a.player, forged), LobbyInteractions.Result.INPUT, "Invalid model hit is rejected");
            var packet = new ServerboundInteractPacket(id, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0), false);
            int before = a.connection.packets.size();
            test.assertValueEqual(LobbyInteractions.entity(a.player, packet), LobbyInteractions.Result.ACCEPTED, "Role model reaches the existing facility action");
            int after = a.connection.packets.size();
            test.assertTrue(after > before, "Existing closed-area feedback is preserved");
            LobbyInteractions.entity(a.player, packet);
            test.assertValueEqual(a.connection.packets.size(), after, "Two network guards dispatch each click once");
            test.assertFalse(session.mutationPending(), "Waiting humans cannot bypass existing role restrictions");
            place(a, LobbyLayout.Landing.CENTRAL);
        }
        void reopenLegacyCamp(String marker, boolean editFloor) {
            var lobby = LobbyWorld.open(server);
            var level = lobby.holdingLevel(); var anchor = BlockPos.containing(lobby.holdingSpawn());
            // No players remain. Reopen the actual persistent level through production migration,
            // with a fixture-owned old manifest and old lecterns (no production test bypass).
            LobbyWorld.shutdown(server);
            for (var facility : LobbyLayout.MONSTER_FACILITIES) {
                var point = LobbyLayout.facility(facility);
                var pos = anchor.offset((int) point.x(), 0, (int) point.z());
                test.assertTrue(level.setBlockAndUpdate(pos, Blocks.LECTERN.defaultBlockState()), "Fixture restores an old v1 role lectern");
            }
            // A v1 camp predates the monster shop: no vaults and no shop signs.
            for (var pos : dev.hardrush.world.CampBlueprint.shopAdditions(anchor).keySet())
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            if (editFloor) level.setBlockAndUpdate(anchor.offset(-8, -1, 10), Blocks.GOLD_BLOCK.defaultBlockState());
            level.save(null, true, false);
            var manifest = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("hardrush/lobby-layout.txt");
            try {
                java.nio.file.Files.writeString(manifest, "hardrush:lobby\nversion=" + LobbyLayout.VERSION
                        + "\nanchor=" + anchor.asLong() + "\n" + marker + "\n");
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            LobbyWorld.open(server);
        }
        void assertUpgradedCamp() {
            var lobby = LobbyWorld.open(server);
            for (var facility : LobbyLayout.Facility.values()) {
                var pos = BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(facility)));
                test.assertTrue(lobby.holdingLevel().getBlockState(pos).equals(dev.hardrush.world.CampBlueprint.facilityBlock(facility)),
                        "Legacy upgrade replaces monster lecterns and adds the shop vaults: " + facility);
            }
        }
        void assertProtectionAndUi() {
            var floor = BlockPos.containing(a.player.position()).below(2);
            // The jump can leave feet above the ground; target the original flat floor directly.
            floor = new BlockPos(floor.getX(), LobbyWorld.open(server).holdingLevel().getRespawnData().pos().getY() - 1, floor.getZ());
            var original = a.player.level().getBlockState(floor);
            a.player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, floor, Direction.UP, 1));
            test.assertFalse(a.player.gameMode.destroyBlock(floor), "Native block breaking cannot bypass lobby protection");
            a.player.getInventory().setItem(0, new ItemStack(Items.STONE));
            a.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false), 2));
            test.assertTrue(a.player.level().getBlockState(floor).equals(original) && a.player.level().getBlockState(floor.above()).isAir(), "Both block packets leave terrain unchanged");
            test.assertFalse(a.player.hurtServer(a.player.level(), a.player.damageSources().playerAttack(b.player), 5), "Lobby bodies cannot be damaged");
            a.player.getInventory().clearContent();
            a.command("hardrush menu"); test.assertTrue(LobbyMenus.isMenu(a.player), "Adventure mode keeps the actual lobby menu available");
            a.player.connection.handleContainerClose(new ServerboundContainerClosePacket(a.player.containerMenu.containerId));
            var book = new BookPort.Book(UUID.randomUUID(), 1, 1, "大厅测试历史书", "HardRush", List.of("只读历史"));
            a.player.getInventory().setItem(0, WrittenBookPort.createItem(new OperationTag(book.roundId(), UUID.randomUUID(), 1), book));
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
            List<Packet<?>> receivedPackets() { return List.copyOf(connection.packets); }
            void login(GameTestSequence sequence) { login(sequence, false); }
            void login(GameTestSequence sequence, boolean active) {
                sequence.thenExecute(() -> { prepare = new PrepareSpawnTask(server, new NameAndId(profile)); prepare.start(packet -> {}); })
                        .thenWaitUntil(() -> await(prepare.tick(), "Native login data loaded"))
                        .thenExecute(() -> {
                            connection = new RecordingConnection(); channel = new EmbeddedChannel(connection); acknowledged = Integer.MIN_VALUE; batchCount = 0;
                            player = prepare.spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false)); prepare.close(); acknowledge();
                            test.assertTrue(PlayerProfiles.isLocked(player), "Native login begins in quarantine");
                            assertFrozen();
                        }).thenWaitUntil(() -> await(active ? !PlayerProfiles.isLocked(player) : PlayerProfiles.canMoveInLobby(player), active ? "Living profile restored" : "Profile verification opens lobby movement"));
            }
            void assertFrozen() {
                acknowledge();
                var position = player.position();
                player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(position.add(.4, 0, 0), 40, 0, true, false));
                test.assertTrue(player.position().distanceToSqr(position) < .000001 && !PlayerProfiles.canMoveInLobby(player), "Pending or failed profiles cannot move");
                acknowledge();
            }
            void move(double x, double y, float yaw, float pitch, boolean ground) {
                acknowledge();
                var target = player.position().add(x, y, 0);
                player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(target, yaw, pitch, ground, false));
                player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
                test.assertTrue(player.position().distanceToSqr(target) < .000001, "Actual inbound movement reaches the server position: target="
                        + target + ", actual=" + player.position() + ", loaded=" + player.connection.hasClientLoaded()
                        + ", mobile=" + PlayerProfiles.canMoveInLobby(player));
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
