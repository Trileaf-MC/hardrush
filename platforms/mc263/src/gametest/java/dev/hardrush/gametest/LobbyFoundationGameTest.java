package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.p1.lobby.LobbyInteractions;
import dev.hardrush.p1.lobby.LobbyProjections;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
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

/** Two native embedded connections; no rendered client, network skin download or player hunt business. */
public final class LobbyFoundationGameTest {
    @GameTest(maxTicks = 60000)
    public void dedicatedFacilitiesProjectionGenerationsAndRecoveryUseProductionGuards(GameTestHelper test) {
        if (!Boolean.getBoolean("hardrush.lobby.testFoundation")) { test.succeed(); return; }
        var f = new Fixture(test); var scenario = new Scenario(f);
        test.onEachTick(() -> { f.clock.accumulateAndGet(System.nanoTime(), Math::max); f.a.tickConnection(); f.b.tickConnection(); });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(P1LobbyWorld.open(f.server).ready()
                && P1LobbyRuntime.sessionState(f.server).filter(state -> state == P1LobbySession.State.OPEN).isPresent(), "Lobby ready"))
                .thenExecute(() -> { f.bind(); scenario.register(); });
        f.a.login(sequence); f.b.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.session.snapshot().orElseThrow().phase() == RoundPhase.WAITING
                        && !f.session.mutationPending(), "Adapters and profiles ready"))
                .thenExecute(() -> {
                    try { f.assertLobby(); scenario.facilities(); scenario.project(); scenario.pagination(); }
                    catch (RuntimeException error) { throw new IllegalStateException("Foundation initial protocol assertions", error); }
                })
                .thenExecute(() -> { scenario.beforeReconnect = scenario.aView.entities().getFirst().entityId(); f.a.disconnect(); })
                .thenExecute(() -> test.assertTrue(scenario.router.projections().view(f.b.player).isPresent(), "A disconnect preserves B mapping"));
        f.a.login(sequence);
        sequence.thenExecute(() -> { scenario.reconnected(); f.reply.set(null); f.session.adapterFailure("Foundation recovery fixture", new IllegalStateException("controlled fault")); })
                .thenWaitUntil(() -> f.await(f.session.state() == P1LobbySession.State.FAILED
                        && scenario.router.projections().view(f.a.player).isEmpty()
                        && scenario.router.projections().view(f.b.player).isEmpty(), "Recovery isolation"))
                .thenExecute(() -> {
                    test.assertTrue(scenario.router.projections().view(f.a.player).isEmpty()
                            && scenario.router.projections().view(f.b.player).isEmpty(), "Recovery removes both observer projections");
                    scenario.useBlock(f.a.player, scenario.rules, 77, InteractionHand.MAIN_HAND);
                    test.assertFalse(P2LobbyUi.isMenu(f.a.player), "Unavailable session cannot open rules");
                    f.session.recheckStorage("fixture", "第1轮恢复门槛验证", f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get() != null && f.reply.get().success(), "Storage recheck complete"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.session.snapshot().orElseThrow().phase(), RoundPhase.RECOVERY_REQUIRED, "Recheck never resumes competition");
                    scenario.useBlock(f.a.player, scenario.rules, 78, InteractionHand.MAIN_HAND);
                    test.assertTrue(P2LobbyUi.isMenu(f.a.player), "Verified body can read rules after storage recheck in recovery");
                    f.a.player.closeContainer();
                    var camp = P1LobbyWorld.open(f.server);
                    var history = BlockPos.containing(camp.layoutPosition(dev.hardrush.core.lobby.LobbyLayout.facility(dev.hardrush.core.lobby.LobbyLayout.Facility.HISTORY)));
                    scenario.teleport(f.a.player, Vec3.atBottomCenterOf(history.south(2)));
                    scenario.useBlock(f.a.player, history, 781, InteractionHand.MAIN_HAND);
                    test.assertTrue(P2LobbyUi.isMenu(f.a.player), "Rechecked healthy history service opens through the real library lectern during recovery");
                    f.a.player.closeContainer();
                    scenario.teleport(f.a.player, camp.holdingSpawn());
                    test.assertFalse(P2LobbyUi.open(f.a.player, LobbyContext.COMPETITION), "Recovery competition menu is sealed");
                    f.historyDrain = f.session.history(1, 0);
                })
                .thenWaitUntil(() -> f.await(f.historyDrain.isDone(), "Read-only history IO drained before audited recovery close"))
                .thenExecute(() -> {
                    f.historyDrain.join();
                    test.assertFalse(P2LobbyUi.isMenu(f.a.player), "Late history query never reopens the closed menu");
                    f.reply.set(null); f.session.closeRecovery("fixture", "第1轮轮换清理验证", f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get()!=null && f.reply.get().success(), "Recovery close committed after read-only IO"))
                .thenWaitUntil(() -> f.await(f.session.snapshot().orElseThrow().sequence() == 2
                        && f.session.snapshot().orElseThrow().phase() == RoundPhase.WAITING
                        && P1PlayerProfiles.canMoveInLobby(f.a.player) && P1PlayerProfiles.canMoveInLobby(f.b.player), "New round ready"))
                .thenExecute(() -> {
                    test.assertTrue(scenario.router.projections().view(f.a.player).isEmpty(), "Rotation leaves no old page");
                    int before = scenario.ruleClicks;
                    scenario.useBlock(f.a.player, scenario.rules, 79, InteractionHand.MAIN_HAND);
                    test.assertValueEqual(scenario.ruleClicks, before, "Old block registration is removed on rotation");
                    scenario.register(); scenario.project();
                    scenario.click(f.a.player, scenario.beforeReconnect);
                    test.assertTrue(scenario.selections.isEmpty(), "Old connection/round ID cannot select a new target");
                    test.assertTrue(scenario.aView.entities().stream().noneMatch(e -> e.entityId() == scenario.beforeReconnect), "No model ID recycling across rounds");
                    scenario.router.projections().clear(); f.a.disconnect(); f.b.disconnect();
                    System.out.println("FOUNDATION_PROTOCOL_PASS: dedicated block, native mannequin metadata, independent viewers, stale IDs, reconnect, recovery and rotation");
                }).thenSucceed();
    }

    private static final class Scenario {
        final Fixture f; final GameTestHelper test;
        final ArrayList<LobbyProjections.Selection> selections = new ArrayList<>();
        final ArrayList<LobbyProjections.Target> targets = new ArrayList<>();
        LobbyInteractions router; BlockPos rules; int ruleClicks; int beforeReconnect;
        LobbyProjections.View aView, bView;
        Scenario(Fixture f) { this.f = f; test = f.test; }
        void register() {
            router = LobbyInteractions.get(f.server).orElseThrow();
            rules = BlockPos.containing(P1LobbyWorld.open(f.server).holdingSpawn()).offset(2, 0, 0);
            place(rules, Blocks.LECTERN);
            router.registerBlock(rules, Blocks.LECTERN, LobbyContext.RULES, p -> { ruleClicks++; P2LobbyUi.open(p, LobbyContext.RULES); });
            f.rules = rules;
        }
        void place(BlockPos pos, net.minecraft.world.level.block.Block block) {
            // Fixture terrain only; production protection is restored BEFORE any input packet.
            try (var scope = dev.hardrush.p2.protection.P2ProtectionRuntime.temporaryConfiguration(f.server,
                    new dev.hardrush.p2.protection.P2ProtectionRuntime.Access() {
                        public java.util.Optional<dev.hardrush.p2.protection.P2ProtectionRuntime.Zone> zone(net.minecraft.server.level.ServerLevel level) { return java.util.Optional.empty(); }
                        public java.util.Optional<dev.hardrush.core.round.Participant> participant(UUID id) { return java.util.Optional.empty(); }
                        public boolean leaveProtection(UUID round, UUID player, int life) { return false; }
                    })) {
                var level = P1LobbyWorld.open(f.server).holdingLevel();
                level.setBlockAndUpdate(pos, block.defaultBlockState());
                test.assertTrue(level.getBlockState(pos).is(block), "Fixture places dedicated block idempotently");
            }
        }
        void useBlock(ServerPlayer p, BlockPos pos, int sequence, InteractionHand hand) {
            p.connection.handleUseItemOn(new ServerboundUseItemOnPacket(hand,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), sequence));
        }
        void facilities() {
            test.assertTrue(dev.hardrush.p2.protection.P2ProtectionRuntime.protectedEntity(f.a.player), "Actual production protection is enabled");
            useBlock(f.a.player, rules, 1, InteractionHand.MAIN_HAND);
            test.assertValueEqual(ruleClicks, 1, "Registered lectern reaches both guards: result=" + router.lastResult(f.a.player)
                    + ", verified=" + P1PlayerProfiles.verifiedLobbyBody(f.a.player));
            test.assertTrue(P2LobbyUi.isMenu(f.a.player), "Native block packet opens the read-only rule context");
            test.assertTrue(f.a.player.containerMenu.getSlot(0).getItem().getHoverName().getString().contains("集结区"), "Rule context has rule content");
            f.a.player.closeContainer();
            var packet = new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(rules), Direction.UP, rules, false), 2);
            test.assertValueEqual(LobbyInteractions.block(f.a.player, packet), LobbyInteractions.Result.ACCEPTED, "First layer accepts");
            test.assertValueEqual(LobbyInteractions.block(f.a.player, packet), LobbyInteractions.Result.ACCEPTED, "Other layer sees same consumed dispatch");
            test.assertValueEqual(ruleClicks, 2, "Guard order cannot double-dispatch the callback");
            f.a.player.closeContainer();
            useBlock(f.a.player, rules, 3, InteractionHand.OFF_HAND);
            test.assertValueEqual(ruleClicks, 2, "Offhand duplicate cannot trigger a second action");
            var invalid = new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(rules).add(1000,0,0), Direction.UP, rules, false), 4);
            f.a.player.connection.handleUseItemOn(invalid);
            test.assertValueEqual(ruleClicks, 2, "Forged hit vector is rejected");
            teleport(f.a.player, P1LobbyWorld.open(f.server).holdingSpawn().add(-10,0,0));
            useBlock(f.a.player, rules, 5, InteractionHand.MAIN_HAND);
            test.assertValueEqual(ruleClicks, 2, "Remote registered-facility request is rejected");
            teleport(f.a.player, P1LobbyWorld.open(f.server).holdingSpawn());
            var chest = rules.offset(0,0,2); place(chest, Blocks.CHEST);
            useBlock(f.a.player, chest, 6, InteractionHand.MAIN_HAND);
            test.assertFalse(P2LobbyUi.isMenu(f.a.player) || f.a.player.containerMenu != f.a.player.inventoryMenu, "Ordinary container stays sealed");
            try { router.registerBlock(chest, Blocks.CHEST, LobbyContext.RULES, p -> {}); throw new AssertionError("Chest registered"); }
            catch (IllegalArgumentException expected) { }
            var stand = new ArmorStand(EntityTypes.ARMOR_STAND, f.a.player.level());
            stand.setPos(f.a.player.position().add(1,0,0)); f.a.player.level().addFreshEntity(stand);
            f.a.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_CHESTPLATE));
            click(f.a.player, stand.getId());
            test.assertTrue(f.a.player.getMainHandItem().is(Items.DIAMOND_CHESTPLATE), "Unregistered entity cannot take equipment");
            stand.discard(); f.a.player.getInventory().clearContent();
            test.assertValueEqual(f.session.snapshot().orElseThrow().rules().huntRule(), RulesConfig.HuntRule.PERSONAL_TARGET, "New rounds use new semantics");
            dev.hardrush.p2.lobby.P2LobbyRuntime.command(f.a.player, "deploy", "");
            test.assertTrue(f.a.player.level() == P1LobbyWorld.open(f.server).holdingLevel(), "Unconnected direct hunt stays closed");
        }
        void project() {
            targets.clear(); selections.clear();
            targets.add(new LobbyProjections.Target(f.a.profile, 1, LobbyProjections.State.AVAILABLE));
            targets.add(new LobbyProjections.Target(f.b.profile, 7, LobbyProjections.State.AWAY));
            for (int i=2; i<21; i++) targets.add(new LobbyProjections.Target(new GameProfile(UUID.randomUUID(), "Model"+i), 1, LobbyProjections.State.AVAILABLE));
            aView = router.projections().show(f.a.player, router.round(), targets, 0, (p,s) -> selections.add(s));
            bView = router.projections().show(f.b.player, router.round(), targets, 1, (p,s) -> selections.add(s));
            test.assertValueEqual(aView.entities().size(), 20, "A sees 20 full models");
            test.assertValueEqual(bView.entities().size(), 1, "B independently sees page 2");
            var first = aView.entities().getFirst();
            test.assertTrue(f.a.connection.packets.stream().filter(ClientboundSetEntityDataPacket.class::isInstance)
                    .map(ClientboundSetEntityDataPacket.class::cast).filter(p -> p.id() == first.entityId())
                    .flatMap(p -> p.packedItems().stream()).anyMatch(d -> d.value() instanceof ResolvableProfile profile
                            && profile.partialProfile().equals(f.a.profile)), "Native metadata retains the entire ORIGINAL profile including textures");
            var data = f.a.connection.packets.stream().filter(ClientboundSetEntityDataPacket.class::isInstance)
                    .map(ClientboundSetEntityDataPacket.class::cast).filter(p -> p.id() == first.entityId()).findFirst().orElseThrow();
            var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), f.server.registryAccess());
            try {
                ClientboundSetEntityDataPacket.STREAM_CODEC.encode(wire, data);
                var decoded = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(wire);
                test.assertTrue(decoded.packedItems().stream().anyMatch(d -> d.value() instanceof ResolvableProfile profile
                        && profile.partialProfile().equals(f.a.profile)), "Original signed texture fields survive the actual outbound native wire codec");
            } finally { wire.release(); }
            for (var id : aView.entities()) test.assertTrue(f.a.player.level().getEntity(id.entityId()) == null, "Projection has no real world entity");
            test.assertValueEqual(f.server.getPlayerList().getPlayerCount(), 2, "Models never count as real players");
            test.assertFalse(f.b.connection.packets.stream().anyMatch(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == first.entityId()), "A projection is never broadcast to B");
        }
        void pagination() {
            var old = aView; var bBefore = bView;
            moveToModel(f.a, old.entities().getFirst().entityId()); click(f.a.player, old.entities().getFirst().entityId());
            test.assertValueEqual(selections.size(), 1, "Actual native right-click reaches the mapped target");
            test.assertValueEqual(selections.getFirst().target(), f.a.profile.id(), "UUID selected rather than display name");
            selections.clear();
            var nextSign = BlockPos.containing(f.a.player.position()).offset(-1,0,0);
            place(nextSign, Blocks.OAK_SIGN);
            router.registerBlock(nextSign, Blocks.OAK_SIGN, LobbyContext.DISPLAY, p -> router.projections().turn(p, 1));
            int packetsBefore = f.b.connection.packets.size();
            useBlock(f.a.player, nextSign, 32, InteractionHand.MAIN_HAND);
            aView = router.projections().view(f.a.player).orElseThrow();
            test.assertValueEqual(aView.page(), 1, "Actual registered pagination-sign packet flips only this observer");
            test.assertTrue(aView.generation() != old.generation(), "Every display has a new generation");
            test.assertValueEqual(router.projections().view(f.b.player).orElseThrow(), bBefore, "A paging preserves B page and IDs");
            test.assertValueEqual(f.b.connection.packets.size(), packetsBefore, "A paging sends no packets to B");
            moveToModel(f.a, aView.entities().getFirst().entityId());
            click(f.a.player, old.entities().getFirst().entityId());
            click(f.a.player, bView.entities().getFirst().entityId());
            test.assertTrue(selections.isEmpty(), "Old page ID and another observer's ID cannot hit the new target");
            click(f.a.player, aView.entities().getFirst().entityId());
            test.assertValueEqual(selections.getFirst().target(), targets.get(20).originalProfile().id(), "Replacement ID maps only to new target");
            selections.clear();
            int previousLifeId = aView.entities().getFirst().entityId();
            aView = router.projections().show(f.a.player, router.round(), List.of(
                    new LobbyProjections.Target(f.a.profile, 2, LobbyProjections.State.AVAILABLE)), 0, (p,s) -> selections.add(s));
            moveToModel(f.a, aView.entities().getFirst().entityId());
            click(f.a.player, previousLifeId);
            test.assertTrue(selections.isEmpty(), "Previous generation cannot select a replacement life");
            click(f.a.player, aView.entities().getFirst().entityId());
            test.assertValueEqual(selections.getFirst().life(), 2, "Selection preserves the bound life sequence");
            selections.clear(); teleport(f.a.player, P1LobbyWorld.open(f.server).holdingSpawn());
            click(f.a.player, aView.entities().getFirst().entityId());
            test.assertTrue(selections.isEmpty(), "Remote model click is rejected");
            aView = router.projections().show(f.a.player, router.round(), targets, 0, (p,s) -> selections.add(s));
            moveToModel(f.a, aView.entities().get(1).entityId()); click(f.a.player, aView.entities().get(1).entityId());
            test.assertTrue(selections.isEmpty(), "Temporarily offline model cannot be selected");
            moveToModel(f.b, bView.entities().getFirst().entityId()); click(f.b.player, bView.entities().getFirst().entityId());
            test.assertValueEqual(selections.size(), 1, "B's own original page remains clickable"); selections.clear();
        }
        void reconnected() {
            test.assertTrue(router.projections().view(f.a.player).isEmpty(), "Replacement connection starts without a page");
            aView = router.projections().show(f.a.player, router.round(), targets, 0, (p,s) -> selections.add(s));
            moveToModel(f.a, aView.entities().getFirst().entityId()); click(f.a.player, beforeReconnect);
            test.assertTrue(selections.isEmpty(), "Old connection's native ID remains invalid");
            teleport(f.a.player, P1LobbyWorld.open(f.server).holdingSpawn());
        }
        void moveToModel(Fixture.Peer peer, int id) {
            var packet = peer.connection.packets.stream().filter(ClientboundAddEntityPacket.class::isInstance)
                    .map(ClientboundAddEntityPacket.class::cast).filter(p -> p.getId()==id).findFirst().orElseThrow();
            test.assertValueEqual(packet.getType(), EntityTypes.MANNEQUIN, "Actual native full-body entity type");
            teleport(peer.player, new Vec3(packet.getX(),packet.getY(),packet.getZ()-2)); peer.acknowledge();
        }
        void teleport(ServerPlayer p, Vec3 pos) { p.connection.teleport(pos.x,pos.y,pos.z,0,0); if (p==f.a.player) f.a.acknowledge(); else f.b.acknowledge(); }
        void click(ServerPlayer p, int id) {
            // Round-trip the vanilla 26.3 wire codec: no page/generation exists in the packet.
            var bytes = io.netty.buffer.Unpooled.buffer();
            try {
                ServerboundInteractPacket.STREAM_CODEC.encode(bytes, new ServerboundInteractPacket(id, InteractionHand.MAIN_HAND, new Vec3(0,1,0), false));
                p.connection.handleInteract(ServerboundInteractPacket.STREAM_CODEC.decode(bytes));
            } finally { bytes.release(); }
        }
    }
    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final AtomicLong clock = new AtomicLong(System.nanoTime());
        final AtomicReference<P1LobbySession.Reply> reply = new AtomicReference<>();
        final Peer a = new Peer("LobbyMovingA"), b = new Peer("LobbyMovingB");
        P1LobbySession session;
        java.util.concurrent.CompletableFuture<?> historyDrain;
        P1PlayerProfiles profiles;
        BlockPos rules;
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
                System.out.println("Lobby foundation stage: " + stage);
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
        final class Peer {
            final GameProfile profile;
            PrepareSpawnTask prepare;
            RecordingConnection connection;
            EmbeddedChannel channel;
            ServerPlayer player;
            int acknowledged = Integer.MIN_VALUE;
            int batchCount;
            int observedFrom;
            Peer(String name) { profile = new GameProfile(UUID.randomUUID(), name,
                    new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", "original-texture-" + name)))); }
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
                if (rules != null) {
                    player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(rules), Direction.UP, rules, false), 80));
                    test.assertFalse(P2LobbyUi.isMenu(player), "Quarantined profile cannot open a registered facility");
                }
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
