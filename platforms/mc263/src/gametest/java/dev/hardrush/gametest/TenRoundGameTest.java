package dev.hardrush.gametest;

import dev.hardrush.HardRushProperties;
import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.round.RoundSession;
import dev.hardrush.book.WrittenBookPort;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.player.ProfileJournal;
import dev.hardrush.player.ProfileTagged;
import dev.hardrush.world.RoundWorldBundle;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** Dedicated headless run: actual runtime listeners, native levels/login/combat, SQLite and profile IO. */
public final class TenRoundGameTest {
    private static volatile AtomicLong accelerated;

    @GameTest(maxTicks = 100000)
    public void celebrationKitsReconnectPortalEarlyReturnAndThreeMinuteTimeout(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.CELEBRATION)) { test.succeed(); return; }
        var f = new Fixture(test);
        test.onEachTick(() -> {
            test.assertFalse(RoundRuntime.sessionState(f.server).filter(s -> s == RoundSession.State.FAILED).isPresent(),
                    "Celebration runtime failed: " + RoundRuntime.status(f.server));
            for (var actor : List.of(f.first, f.offline)) if (actor.player != null) f.acknowledge(actor.player);
        });
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(RoundRuntime.sessionState(f.server)
                .filter(s -> s == RoundSession.State.OPEN).isPresent(), "Runtime bootstrap"))
                .thenExecute(f::installClock);
        f.login(sequence, f.first); f.login(sequence, f.offline);
        f.celebrationRound(sequence, true);
        sequence.thenExecute(() -> {
            f.assertKit(f.first.player); f.assertKit(f.offline.player);
            f.first.player.setOnGround(false);
            test.assertTrue(f.first.player.tryToStartFallFlying(), "Human can begin native elytra gliding");
            f.first.player.connection.handleUseItem(new ServerboundUseItemPacket(net.minecraft.world.InteractionHand.MAIN_HAND, 1, 0, 0));
            test.assertValueEqual(f.first.player.getInventory().getItem(0).getCount(), 15, "Actual use-item packet consumes one boost rocket");
            f.first.player.stopFallFlying();
            var chest = BlockPos.containing(f.first.player.position()).below();
            f.first.player.level().setBlockAndUpdate(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            f.first.player.getInventory().setSelectedSlot(1);
            f.first.player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(chest), net.minecraft.core.Direction.UP, chest, false), 2));
            test.assertValueEqual(f.first.player.getInventory().getItem(1).getCount(), 15, "Native block-use packet launches a colored rocket");
            test.assertTrue(f.first.player.containerMenu == f.first.player.inventoryMenu, "Rocket use on a chest never opens its external container");
            f.first.player.getInventory().setSelectedSlot(0);
            f.disconnect(f.first);
        });
        f.login(sequence, f.first);
        sequence.thenWaitUntil(() -> f.await(dev.hardrush.celebration.CelebrationRuntime.active(f.first.player), "Active ceremony reconnect restored"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.first.player.getInventory().getItem(0).getCount(), 15, "Reconnect does not refill consumed rockets");
                    f.portal(f.offline.player);
                })
                .thenWaitUntil(() -> f.await(LobbyWorld.isLobby(f.offline.player.level()) && f.profiles.isReady(f.offline.profile.id(), f.round()), "First player durably returned"))
                .thenExecute(() -> {
                    test.assertTrue(f.round().phase() != RoundPhase.ROTATING, "One remaining online End player prevents early rotation");
                    test.assertFalse(f.offline.player.getInventory().contains(stack -> stack.is(Items.ELYTRA) || stack.is(Items.FIREWORK_ROCKET)), "Returned player has no temporary kit");
                    f.disconnect(f.offline);
                });
        f.login(sequence, f.offline);
        sequence.thenWaitUntil(() -> f.await(LobbyWorld.isLobby(f.offline.player.level()) && f.profiles.isReady(f.offline.profile.id(), f.round()), "Returned reconnect stays in lobby"))
                .thenExecute(() -> { f.frozenTime = f.round().challengeNanos(); f.portal(f.first.player); })
                .thenWaitUntil(() -> f.await(f.round().sequence() == 2 && f.round().phase() == RoundPhase.WAITING
                        && f.profiles.isReady(f.first.profile.id(), f.round()) && f.profiles.isReady(f.offline.profile.id(), f.round()), "All returned creates exactly one next round"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.worlds.state(), RoundWorldBundle.State.CLOSED, "Early return still closes native world IO");
                    test.assertTrue(f.first.player.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "No elytra crosses into next round");
                });
        f.celebrationRound(sequence, false);
        sequence.thenWaitUntil(() -> f.await(LobbyWorld.isLobby(f.offline.player.level()) && f.profiles.isReady(f.offline.profile.id(), f.round()), "Non-End human automatically returns"))
                .thenExecute(() -> {
                    f.assertKit(f.first.player);
                    test.assertFalse(f.offline.player.getInventory().contains(stack -> stack.is(Items.FIREWORK_ROCKET)), "Nether player is not issued a kit");
                    f.advance(89);
                })
                .thenWaitUntil(() -> f.await(f.session.view().orElseThrow().remainingPhaseMillis() == 1_000 && !f.session.mutationPending(), "89 seconds leaves one second"))
                .thenExecute(() -> {
                    test.assertTrue(dev.hardrush.celebration.CelebrationRuntime.active(f.first.player), "Celebration remains usable before deadline");
                    f.disconnect(f.offline); // Offline lobby player cannot block timeout or release.
                    f.advance(1);
                })
                .thenWaitUntil(() -> f.await(f.round().sequence() == 3 && f.round().phase() == RoundPhase.WAITING
                        && f.profiles.isReady(f.first.profile.id(), f.round()), "90-second timeout evacuates and rotates"))
                .thenExecute(() -> {
                    test.assertTrue(LobbyWorld.isLobby(f.first.player.level()), "Timeout returns remaining celebrant to permanent lobby");
                    test.assertValueEqual(f.worlds.state(), RoundWorldBundle.State.CLOSED, "Timeout closes old native IO");
                });
        f.login(sequence, f.offline);
        f.celebrationRound(sequence, true);
        sequence.thenExecute(() -> f.portal(f.first.player))
                .thenWaitUntil(() -> f.await(LobbyWorld.isLobby(f.first.player.level()) && f.profiles.isReady(f.first.profile.id(), f.round()), "Third ceremony first return"))
                .thenExecute(() -> f.disconnect(f.offline))
                .thenWaitUntil(() -> f.await(f.round().sequence() == 4 && f.round().phase() == RoundPhase.WAITING
                        && f.profiles.isReady(f.first.profile.id(), f.round()), "Disconnected End celebrant does not block early finish"))
                .thenExecute(() -> { f.disconnect(f.first); accelerated = null; }).thenSucceed();
    }

    /** Called only by the separately packaged GameTest mixin; ordinary suites retain the system clock. */
    public static long observationTime() {
        if (ServerRestartRegression.clockInstalled()) return ServerRestartRegression.observationTime();
        var clock = accelerated;
        return clock == null ? System.nanoTime() : clock.get();
    }

    @GameTest(maxTicks = 200000)
    public void tenNativeRoundsReleaseWorldsAndKeepOfflineProfilesAndResultsIsolated(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.TEN_ROUNDS)) { test.succeed(); return; }
        test.assertTrue(HardRushProperties.competitionEnabled(), "Dedicated regression requires the real enabled runtime");
        var fixture = new Fixture(test);
        // Outside the wait sequence: failures here are terminal instead of being retried as wait assertions.
        test.onEachTick(() -> test.assertFalse(RoundRuntime.sessionState(fixture.server)
                .filter(state -> state == RoundSession.State.FAILED).isPresent(),
                "Actual runtime failed: " + RoundRuntime.status(fixture.server)));
        var sequence = test.startSequence()
                .thenWaitUntil(() -> fixture.await(RoundRuntime.sessionState(fixture.server)
                        .filter(state -> state == RoundSession.State.OPEN).isPresent(), "Runtime bootstrap"))
                .thenExecute(fixture::installClock);
        fixture.login(sequence, fixture.first);
        fixture.login(sequence, fixture.offline);
        for (int round = 1; round <= 10; round++) fixture.round(sequence, round);
        sequence.thenWaitUntil(() -> fixture.await(fixture.round().phase() == RoundPhase.WAITING
                        && !fixture.session.mutationPending() && fixture.profiles.isReady(fixture.first.profile.id(), fixture.round()),
                "Eleventh round is prepared after the tenth actual release"))
                .thenWaitUntil(() -> fixture.await(Fixture.book(fixture.first.player, fixture.ids.getLast()), "Tenth result's actual book arrives in the eleventh lobby"))
                .thenExecute(() -> fixture.checked = fixture.session.ensureBook(fixture.first.profile.id(), fixture.ids.getLast(), 1, 1))
                .thenWaitUntil(() -> fixture.awaitBookAck(fixture.first, fixture.ids.getLast()))
                .thenExecute(fixture::requestFinalHistory)
                .thenWaitUntil(() -> fixture.await(fixture.checked.isDone(), "Final persisted history query"))
                .thenExecute(() -> {
                    fixture.checked.join();
                    fixture.disconnect(fixture.first);
                    fixture.disconnect(fixture.offline);
                    accelerated = null;
                }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final Actor first = new Actor("TenRoundA");
        final Actor offline = new Actor("TenRoundB");
        final ArrayList<UUID> ids = new ArrayList<>();
        final HashSet<Long> seeds = new HashSet<>();
        final ArrayList<net.minecraft.resources.ResourceKey<Level>> releasedKeys = new ArrayList<>();
        final ArrayList<java.nio.file.Path> manifests = new ArrayList<>();
        RoundSession session;
        PlayerProfiles profiles;
        RoundWorldBundle worlds;
        ServerLevel[] levels;
        EnderDragonFight fight;
        long frozenTime;
        CompletableFuture<?> checked;
        Vec3 savedPosition;
        String savedDimension;
        Fixture(GameTestHelper test) { this.test = test; server = test.getLevel().getServer(); }

        void installClock() {
            session = service("SESSIONS", RoundSession.class);
            profiles = service("PROFILES", PlayerProfiles.class);
            test.assertValueEqual(round().sequence(), 1L, "The dedicated GameTest storage starts at round one");
            test.assertValueEqual(round().rules().mode(), GameMode.NORMAL, "The runtime uses ordinary production rules");
            accelerated = new AtomicLong(System.nanoTime());
            // Reflection is confined to the test artifact, leaving production lifecycle/admission intact.
            try {
                var field = RoundSession.class.getDeclaredField("clock");
                field.setAccessible(true);
                field.set(session, (MonotonicClock) accelerated::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }

        void celebrationRound(GameTestSequence sequence, boolean secondInEnd) {
            sequence.thenWaitUntil(() -> await(round().phase() == RoundPhase.WAITING && !session.mutationPending(), "Waiting round"))
                    .thenExecute(() -> { worlds = service("WORLDS", RoundWorldBundle.class); command(first, "hardrush join"); })
                    .thenWaitUntil(() -> await(!session.mutationPending() && round().participants().containsKey(first.profile.id()), "First join"))
                    .thenExecute(() -> command(first, "hardrush ready"))
                    .thenWaitUntil(() -> await(round().phase() == RoundPhase.COUNTDOWN && !session.mutationPending(), "Ready countdown"))
                    .thenExecute(() -> advance(30))
                    .thenWaitUntil(() -> await(round().phase() == RoundPhase.RUNNING && !PlayerProfiles.isLocked(first.player) && !session.mutationPending(), "First admitted"))
                    .thenExecute(() -> command(offline, "hardrush join"))
                    .thenWaitUntil(() -> await(!PlayerProfiles.isLocked(offline.player) && !session.mutationPending(), "Second admitted"))
                    .thenExecute(() -> {
                        for (var actor : List.of(first, offline)) {
                            for (int slot = 0; slot < 36; slot++) actor.player.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
                            actor.player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                        }
                        move(first.player, worlds.level(Level.END), new Vec3(.5, 128, .5));
                        move(offline.player, worlds.level(secondInEnd ? Level.END : Level.NETHER), new Vec3(2.5, 128, .5));
                        fight = new EnderDragonFight(false, false, false, Optional.empty(), 0, Optional.empty(),
                                Optional.of(new BlockPos(0, 64, 0)), List.of(), List.of());
                        var end = worlds.level(Level.END);
                        fight.init(end, end.getSeed(), BlockPos.ZERO); fight.skipArenaLoadedCheck(); end.setDragonFight(fight);
                        end.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(0, 0), 3);
                    })
                    .thenWaitUntil(() -> await(fight.dragonUUID() != null && worlds.level(Level.END).getEntity(fight.dragonUUID()) instanceof EnderDragon, "Native first dragon"))
                    .thenExecute(() -> {
                        var dragon = (EnderDragon) worlds.level(Level.END).getEntity(fight.dragonUUID());
                        dragon.snapTo(.5, 68, .5, 0, 0); dragon.setNoAi(true);
                        dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING); dragon.setHealth(.1f);
                        first.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD)); first.player.attack(dragon.head);
                    })
                    .thenWaitUntil(() -> await(round().result() != null && dev.hardrush.celebration.CelebrationRuntime.active(first.player)
                            && (!secondInEnd || dev.hardrush.celebration.CelebrationRuntime.active(offline.player)) && !session.mutationPending(), "Durable kit applied"));
        }

        void assertKit(ServerPlayer player) {
            test.assertTrue(player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                    && player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.UNBREAKABLE), "Native unbreakable elytra equipped despite full original inventory");
            var colors = new HashSet<Integer>();
            for (int slot = 0; slot < 16; slot++) {
                var stack = player.getInventory().getItem(slot);
                test.assertTrue(dev.hardrush.celebration.CelebrationKit.isRocket(stack) && stack.getCount() == 16, "Sixteen temporary rockets per color");
                colors.add(stack.get(DataComponents.FIREWORKS).explosions().getFirst().colors().getInt(0));
            }
            test.assertValueEqual(colors.size(), 16, "Sixteen distinct native firework colors");
        }

        void portal(ServerPlayer player) {
            var destination = ((net.minecraft.world.level.block.EndPortalBlock) net.minecraft.world.level.block.Blocks.END_PORTAL)
                    .getPortalDestination(player.level(), player, BlockPos.containing(player.position()));
            test.assertTrue(destination == null, "Native exit portal is intercepted before bed/anchor lookup");
        }

        RoundSnapshot round() { return RoundRuntime.snapshot(server).orElseThrow(); }
        void advance(long seconds) { accelerated.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); }
        void await(boolean condition, String message) {
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + RoundRuntime.status(server));
        }

        void login(GameTestSequence sequence, Actor actor) {
            sequence.thenExecute(() -> {
                actor.prepare = new PrepareSpawnTask(server, new NameAndId(actor.profile));
                actor.prepare.start(packet -> {});
            }).thenWaitUntil(() -> await(actor.prepare.tick(), "Native login loads the saved UUID"))
                    .thenExecute(() -> {
                        actor.connection = new RecordingConnection();
                        actor.channel = new EmbeddedChannel(actor.connection);
                        actor.player = actor.prepare.spawnPlayer(actor.connection, CommonListenerCookie.createInitial(actor.profile, false));
                        actor.prepare.close();
                        acknowledge(actor.player);
                        test.assertTrue(PlayerProfiles.isLocked(actor.player), "Actual login is quarantined before any participation");
                        test.assertTrue(actor.player.level() == LobbyWorld.open(server).holdingLevel(), "Saved competition coordinates cannot enter before verification");
                    }).thenWaitUntil(() -> await(profiles.isReady(actor.profile.id(), round()), "Durable native profile conversion"));
        }

        void disconnect(Actor actor) {
            if (actor.player == null) return;
            // Fabric's DISCONNECT event is hooked on Connection.handleDisconnection, before the listener.
            actor.connection.disconnect(Component.literal("Ten-round headless reconnect fixture"));
            actor.connection.handleDisconnection();
            test.assertTrue(server.getPlayerList().getPlayer(actor.profile.id()) == null, "Native listener removes the UUID");
            test.assertFalse(profiles.manages(actor.player), "Fabric disconnect releases the profile entry");
            actor.channel.finishAndReleaseAll();
            actor.player = null;
        }

        void round(GameTestSequence sequence, int number) {
            if (number == 5) login(sequence, offline);
            sequence.thenWaitUntil(() -> await(round().sequence() == number && round().phase() == RoundPhase.WAITING
                            && !session.mutationPending() && profiles.isReady(first.profile.id(), round()), "Round " + number + " WAITING"))
                    .thenExecute(() -> {
                        worlds = service("WORLDS", RoundWorldBundle.class);
                        test.assertTrue(ids.add(round().roundId()) && new HashSet<>(ids).size() == ids.size(), "Every round has a new UUID");
                        test.assertTrue(seeds.add(round().seed()), "Every actual rotation chooses a new seed");
                        test.assertTrue(round().participants().isEmpty(), "New round has no previous participants");
                        test.assertValueEqual(session.endEntrants(), 0L, "End entrants reset across rounds");
                        levels = new ServerLevel[]{worlds.level(Level.OVERWORLD), worlds.level(Level.NETHER), worlds.level(Level.END)};
                        manifests.add(worlds.manifestPath());
                        for (var level : levels) {
                            test.assertTrue(server.getLevel(level.dimension()) == level && RoundWorldBundle.owns(level), "Three actual levels are registered to this round");
                            test.assertValueEqual(level.getSeed(), round().seed(), "Actual level seed agrees with SQLite");
                        }
                        test.assertTrue(releasedKeys.stream().allMatch(key -> server.getLevel(key) == null), "Previously closed worlds do not return to the server registry");
                        fresh(first);
                        if (number == 5) fresh(offline);
                    });
            if (number > 1) {
                sequence.thenWaitUntil(() -> await(book(first.player, ids.get(number - 2)), "Previous result delivered as a real written book"))
                        .thenExecute(() -> checked = session.ensureBook(first.profile.id(), ids.get(number - 2), 1, 1))
                        .thenWaitUntil(() -> awaitBookAck(first, ids.get(number - 2)));
            }
            sequence.thenExecute(() -> command(first, "hardrush join"))
                    .thenWaitUntil(() -> await(round().participants().containsKey(first.profile.id()) && !session.mutationPending(), "Join committed"))
                    .thenExecute(() -> command(first, "hardrush ready"))
                    .thenWaitUntil(() -> await(round().phase() == RoundPhase.COUNTDOWN && !session.mutationPending(), "Native command starts countdown"))
                    .thenExecute(() -> advance(30))
                    .thenWaitUntil(() -> await(round().phase() == RoundPhase.RUNNING && !session.mutationPending()
                            && !PlayerProfiles.isLocked(first.player), "Actual runtime admits committed human"))
                    .thenExecute(() -> {
                        test.assertTrue(first.player.level() == levels[0], "Runtime sends the player into this round's real overworld");
                        if (first.stale != null) {
                            int pending = progressPending();
                            session.recordProgress(first.stale);
                            test.assertValueEqual(progressPending(), pending, "Old-round callback cannot even enqueue IO in an unlocked RUNNING round");
                        }
                        test.assertValueEqual(session.endEntrants(), 0L, "Old-round End callback is rejected in an unlocked RUNNING round");
                        test.assertValueEqual(((ProfileTagged) first.player).hardrush$lifeSequence(), 1, "New round begins with its own first life");
                        first.player.getInventory().setItem(2, new ItemStack(Items.DIAMOND, number));
                        first.player.getEnderChestInventory().setItem(0, new ItemStack(Items.EMERALD, number));
                        first.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 12000));
                        first.player.giveExperiencePoints(100);
                        first.player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(
                                levels[0].dimension(), worlds.spawn(), 0, 0), true), false);
                        var progress = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
                        progress.value().criteria().keySet().forEach(key -> first.player.getAdvancements().award(progress, key));
                        move(first.player, levels[1], new Vec3(.5, 128, .5));
                        test.assertTrue(first.player.level() == levels[1], "Native transition stays within this round's nether");
                        move(first.player, levels[0], Vec3.atBottomCenterOf(worlds.spawn()));
                    });
            if (number == 1 || number == 5) {
                sequence.thenExecute(() -> command(offline, "hardrush join"))
                        .thenWaitUntil(() -> await(!session.mutationPending() && !PlayerProfiles.isLocked(offline.player), "Drop-in human profile admission"))
                        .thenExecute(() -> {
                            offline.player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
                            offline.player.getEnderChestInventory().setItem(0, new ItemStack(Items.LAPIS_LAZULI, 9));
                            offline.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 12000));
                            move(offline.player, levels[1], new Vec3(40.5, 128, 40.5));
                            disconnect(offline);
                        }).thenWaitUntil(() -> await(!session.mutationPending() && !round().participants().get(offline.profile.id()).online(), "Offline identity is durable"));
            }
            if (number == 3) {
                sequence.thenExecute(() -> {
                    move(first.player, levels[1], new Vec3(8.5, 128, 8.5));
                    savedPosition = first.player.position(); savedDimension = first.player.level().dimension().identifier().toString();
                    disconnect(first);
                }).thenWaitUntil(() -> await(!session.mutationPending() && !round().participants().get(first.profile.id()).online(), "Same-round disconnect pauses time"));
                login(sequence, first);
                sequence.thenWaitUntil(() -> await(!session.mutationPending() && !PlayerProfiles.isLocked(first.player), "Same-round native reconnect resumes"))
                        .thenExecute(() -> {
                            test.assertValueEqual(first.player.level().dimension().identifier().toString(), savedDimension, "Same-round reconnect restores actual dimension");
                            test.assertTrue(first.player.position().distanceTo(savedPosition) < .02, "Same-round reconnect restores position");
                            test.assertValueEqual(first.player.getInventory().getItem(2).getCount(), 3, "Same-round inventory is preserved");
                            test.assertValueEqual(first.player.getEnderChestInventory().getItem(0).getCount(), 3, "Same-round ender inventory is preserved");
                        });
            }
            if (number == 2) {
                sequence.thenExecute(() -> test.assertTrue(first.player.hurtServer(first.player.level(),
                                first.player.damageSources().genericKill(), Float.MAX_VALUE), "Native ordinary death damage is applied"))
                        .thenWaitUntil(() -> await(!session.mutationPending() && round().participants().get(first.profile.id()).role() == ParticipantRole.HUMAN_RESPAWNING, "Native death commits ordinary respawn role"))
                        .thenExecute(() -> {
                            first.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                            first.player = first.player.connection.player;
                            acknowledge(first.player);
                        }).thenWaitUntil(() -> await(!session.mutationPending() && round().participants().get(first.profile.id()).lifeSequence() == 2
                                && !PlayerProfiles.isLocked(first.player), "Native replacement waits for its durable next life"));
            }
            sequence.thenExecute(() -> {
                advance(number);
                move(first.player, levels[2], new Vec3(.5, 128, .5));
                fight = new EnderDragonFight(false, false, false, Optional.empty(), 0, Optional.empty(),
                        Optional.of(new BlockPos(0, 64, 0)), List.of(), List.of());
                fight.init(levels[2], levels[2].getSeed(), BlockPos.ZERO);
                fight.skipArenaLoadedCheck(); // No graphical client acknowledges arena chunks in this fixture.
                levels[2].setDragonFight(fight);
                levels[2].getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL, new ChunkPos(0, 0), 3);
            }).thenWaitUntil(() -> await(fight.dragonUUID() != null && levels[2].getEntity(fight.dragonUUID()) instanceof EnderDragon,
                    "Actual first dragon is spawned by the native fight"))
                    .thenExecute(() -> {
                        var dragon = (EnderDragon) levels[2].getEntity(fight.dragonUUID());
                        dragon.snapTo(.5, 68, .5, 0, 0); dragon.setOldPosAndRot(); dragon.setNoAi(true);
                        dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);
                        dragon.setHealth(.1f); // Existing low-health fixture; actual player attack and causal hooks remain native.
                        first.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                        first.player.attack(dragon.head);
                        test.assertTrue(session.competitionLocked(), "Native first-dragon lethal capture freezes the actual runtime");
                    }).thenWaitUntil(() -> await(round().result() != null && !session.mutationPending(), "Unique native terminal is committed"))
                    .thenExecute(() -> advance(1))
                    .thenWaitUntil(() -> await(round().phase() == RoundPhase.CELEBRATING && !session.mutationPending(), "Unique result is committed and celebration begins"))
                    .thenExecute(() -> {
                        test.assertValueEqual(round().result().winner(), first.profile.id(), "Actual attack UUID is the winner");
                        test.assertValueEqual(round().challengeMillis(), number * 1000L, "Only this round's active interval is scored");
                        frozenTime = round().challengeNanos();
                        checked = session.participants(round().roundId(), 20, 0).thenAccept(roster -> {
                            test.assertValueEqual(roster.size(), number == 1 || number == 5 ? 2 : 1, "Offline players are not fabricated in absent rounds");
                            var a = roster.stream().filter(value -> value.playerId().equals(first.profile.id())).findFirst().orElseThrow();
                            test.assertTrue(a.advancementCount() >= 1 && a.firstEndNanos() == frozenTime, "Native progress and first End entry persist in this round");
                            test.assertValueEqual(a.deathCount(), number == 2 ? 1 : 0, "Native deaths do not leak across rounds");
                            test.assertValueEqual(a.revivalCount(), number == 2 ? 1 : 0, "Native ordinary respawn is counted once");
                        });
                    }).thenWaitUntil(() -> await(checked.isDone(), "Per-round records are read after atomic terminal"))
                    .thenExecute(() -> {
                        checked.join();
                        test.assertTrue(first.connection.packets.stream().filter(ClientboundTabListPacket.class::isInstance)
                                .map(ClientboundTabListPacket.class::cast).anyMatch(packet -> packet.footer().getString().contains("尾刀：TenRoundA")),
                                "Actual runtime publishes the winner through native TAB packets");
                        // A delayed previous progress callback must never count toward the next round.
                        var stale = new ProgressWrite(round().roundId(), UUID.randomUUID(), first.profile.id(), "TenRoundA",
                                Set.of("minecraft:story/upgrade_tools"), "a".repeat(64), true, frozenTime);
                        first.stale = stale;
                        advance(601);
                    }).thenWaitUntil(() -> await(round().sequence() == number + 1 && !session.mutationPending(), "Actual native release creates the next round"))
                    .thenExecute(() -> {
                        test.assertValueEqual(worlds.state(), RoundWorldBundle.State.CLOSED, "Old bundle completed actual native IO close");
                        for (var level : levels) {
                            test.assertTrue(server.getLevel(level.dimension()) == null && !RoundWorldBundle.owns(level), "Each old native level is deregistered and no longer owned");
                            releasedKeys.add(level.dimension());
                        }
                        test.assertTrue(first.player.level() == LobbyWorld.open(server).holdingLevel() && PlayerProfiles.isLocked(first.player), "Actual runtime suspends the player in the permanent lobby");
                        test.assertTrue(Files.exists(worlds.manifestPath()), "Rotation retains every old ownership manifest");
                        assertReleased(worlds);
                        session.recordProgress(first.stale);
                        org.slf4j.LoggerFactory.getLogger("hardrush-round-ten-rounds").info(
                                "Ten-round native round {} released: id={}, seed={}, resultMillis={}, removedDimensions={}, retainedManifests={}",
                                number, ids.getLast(), levels[0].getSeed(), frozenTime / 1_000_000, releasedKeys.size(), manifests.size());
                        // Drop fixture references too; retained identifiers/manifests cannot retain chunk/world objects.
                        worlds = null; levels = null; fight = null; first.connection.packets.clear();
                    });
        }

        void fresh(Actor actor) {
            test.assertFalse(actor.player.getInventory().contains(stack -> !stack.isEmpty() && !WrittenBookPort.isHistoryBook(stack)), "Cross-round ordinary inventory is empty");
            test.assertTrue(actor.player.getEnderChestInventory().isEmpty(), "Cross-round ender inventory is empty");
            test.assertTrue(actor.player.getActiveEffects().isEmpty() && actor.player.totalExperience == 0, "Cross-round effects and XP are cleared");
            test.assertTrue(actor.player.getRespawnConfig() == null, "Cross-round bed or anchor binding is cleared");
            var progress = server.getAdvancements().get(Identifier.parse("minecraft:story/mine_stone"));
            test.assertFalse(actor.player.getAdvancements().getOrStartProgress(progress).hasProgress(), "Cross-round native advancements are cleared");
            test.assertValueEqual(((ProfileTagged) actor.player).hardrush$generation().orElseThrow(),
                    new ProfileJournal.Generation(round().roundId(), round().sequence()), "Profile is stamped for this round only");
        }

        void requestFinalHistory() {
            test.assertValueEqual(round().sequence(), 11L, "Ten complete rotations reached the eleventh round");
            test.assertValueEqual(releasedKeys.size(), 30, "All thirty native dimension registrations were released");
            test.assertTrue(manifests.stream().allMatch(Files::exists), "Every historical manifest is retained");
            checked = session.history(20, 0).thenCombine(session.leaderboard(round().rules().fingerprint(), 10), (history, board) -> {
                test.assertValueEqual(history.size(), 10, "Exactly ten unique native results persisted");
                test.assertValueEqual(board.size(), 10, "Ten trusted results remain in their rules leaderboard");
                for (int index = 0; index < 10; index++) {
                    var result = board.get(index);
                    test.assertValueEqual(result.round().roundId(), ids.get(index), "Sorted history points to the original native UUID");
                    test.assertValueEqual(result.round().challengeMillis(), (index + 1) * 1000L, "Scores remain isolated after ten world rotations");
                }
                test.assertTrue(round().participants().isEmpty() && session.endEntrants() == 0, "Late old callbacks cannot populate the eleventh round");
                return null;
            });
        }

        void command(Actor actor, String command) {
            if (SpatialInputFixtures.handle(actor.player, command)) return;
            try { server.getCommands().getDispatcher().execute(command, actor.player.createCommandSourceStack()); }
            catch (Exception error) { throw new IllegalStateException(command, error); }
        }
        void move(ServerPlayer player, ServerLevel level, Vec3 position) {
            player.teleport(new TeleportTransition(level, position, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            acknowledge(player);
        }
        void acknowledge(ServerPlayer player) {
            var actor = player.getUUID().equals(first.profile.id()) ? first : offline;
            // The embedded peer follows the native teleport and dimension-loaded acknowledgements.
            actor.connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance)
                    .map(ClientboundPlayerPositionPacket.class::cast).reduce((previous, current) -> current)
                    .ifPresent(packet -> player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                            packet.id(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot())));
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        static boolean book(ServerPlayer player, UUID roundId) {
            return player.getInventory().contains(stack -> WrittenBookPort.isHistoryBook(stack)
                    && stack.get(DataComponents.CUSTOM_DATA).copyTag().getStringOr("hardrush_book_round", "").equals(roundId.toString()));
        }
        void awaitBookAck(Actor actor, UUID sourceRound) {
            // Item insertion precedes save verification/ACK. Query one claim at a time until that barrier commits.
            if (checked.isDone() && !((dev.hardrush.core.record.BookClaim) checked.join()).delivered()) {
                checked = session.ensureBook(actor.profile.id(), sourceRound, 1, 1);
            }
            await(checked.isDone() && ((dev.hardrush.core.record.BookClaim) checked.join()).delivered(), "Actual inventory save and durable book ACK");
        }
        void assertReleased(RoundWorldBundle bundle) {
            try {
                for (String name : new String[]{"held", "inFlight", "requested"}) {
                    var field = RoundWorldBundle.class.getDeclaredField(name); field.setAccessible(true);
                    var value = field.get(bundle);
                    test.assertTrue(value instanceof Map<?, ?> map ? map.isEmpty() : ((Collection<?>) value).isEmpty(), "Old world task/ticket references released: " + name);
                }
                var field = RoundWorldBundle.class.getDeclaredField("CLAIMS"); field.setAccessible(true);
                var claims = ((Map<?, ?>) field.get(null)).get(server);
                test.assertTrue(claims == null || !((Map<?, ?>) claims).containsKey(bundle.roundId()), "Static ownership registry no longer retains the old round");
                var closed = RoundWorldBundle.class.getDeclaredField("closedNativeLevels"); closed.setAccessible(true);
                test.assertValueEqual(((Collection<?>) closed.get(bundle)).size(), 3, "Native IO close completed for all three old levels");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        int progressPending() {
            try {
                var field = RoundSession.class.getDeclaredField("progressPending"); field.setAccessible(true);
                return field.getInt(session);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        <T> T service(String name, Class<T> type) {
            try {
                var field = RoundRuntime.class.getDeclaredField(name); field.setAccessible(true);
                return type.cast(((Map<?, ?>) field.get(null)).get(server));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    }

    private static final class Actor {
        final GameProfile profile;
        ServerPlayer player;
        PrepareSpawnTask prepare;
        RecordingConnection connection;
        EmbeddedChannel channel;
        ProgressWrite stale;
        Actor(String name) { profile = new GameProfile(UUID.randomUUID(), name); }
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
