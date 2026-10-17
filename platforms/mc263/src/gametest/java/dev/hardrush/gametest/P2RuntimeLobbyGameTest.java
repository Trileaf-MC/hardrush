package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.hunt.HuntVotingService;
import dev.hardrush.core.round.*;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p1.P1LobbySession;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.world.P1RoundWorldBundle;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
import dev.hardrush.p2.monster.P2MonsterRuntime;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Opt-in production runtime chain with two embedded native connections and real SQLite/profile IO. */
public final class P2RuntimeLobbyGameTest {
    private static final int ARENA_TICKET_RADIUS = 7;
    private static final java.util.Set<UUID> FROZEN_SUMMON_OWNERS = new java.util.HashSet<>();
    private static final TicketType LOOT_FIXTURE = new TicketType(TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    static {
        // Freeze only this lifecycle fixture's owned native children at world insertion, before their first AI tick.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Zombie child && FROZEN_SUMMON_OWNERS.contains(MonsterRoles.summonOwner(child))) child.setNoAi(true);
        });
    }

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
                .thenWaitUntil(() -> f.await(f.arenaTicking(), "The entire private arena reaches native entity simulation before lifecycle checks"))
                .thenExecute(() -> f.target.move(f.anchor))
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
                    f.monster.openAndClick(Items.ROTTEN_FLESH);
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.ZOMBIE && !f.session.mutationPending(), "Role choice durably saved"))
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
                    f.equipWorldSword();
                    f.test.assertTrue(MonsterRoles.summon(f.monster.player), "Safe preflight accepts the formal summon for durable admission");
                    f.test.assertTrue(P2MonsterRuntime.casting(f.monster.player), "The accepted cast remains pending before the SQLite callback");
                    f.blockSummonRing();
                    f.test.assertValueEqual(f.summons(), 0, "A pending cast never creates children before the safe-point recheck");
                })
                .thenWaitUntil(() -> f.awaitSkillEffect(!P2MonsterRuntime.casting(f.monster.player) && !f.session.mutationPending()
                        && !f.monster.participant().monsterSkillReadyAtMillis().containsKey(MonsterSkill.ZOMBIE_SUMMON),
                        "Changed safe points refund the committed skill cooldown through real SQLite compensation"))
                .thenExecute(() -> {
                    f.test.assertValueEqual(f.summons(), 0, "Failed post-commit safety revalidation never emits a partial summon pair");
                    f.test.assertFalse(MonsterRoles.summon(f.monster.player), "A fully blocked ring fails preflight immediately");
                    f.test.assertFalse(P2MonsterRuntime.casting(f.monster.player), "Failed preflight never reserves a cast");
                    f.test.assertFalse(f.monster.participant().monsterSkillReadyAtMillis().containsKey(MonsterSkill.ZOMBIE_SUMMON),
                            "Failed preflight leaves the durable cooldown unused");
                    f.restoreSummonRing();
                    f.monster.command("hardrush skill");
                    f.monster.command("hardrush skill");
                    f.test.assertValueEqual(f.summons(), 0, "Pending skill requests emit no children before SQLite commits");
                })
                .thenWaitUntil(() -> f.awaitSkillEffect(f.summons() == 2 && !f.session.mutationPending(), "Exactly one durable summon produces two native babies"))
                .thenExecute(() -> {
                    f.summonReadyAt = f.monster.participant().monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON);
                    f.test.assertTrue(f.summonReadyAt > System.currentTimeMillis(), "The successful summon persists its real epoch cooldown");
                    f.monster.command("hardrush skill");
                    f.test.assertValueEqual(f.summons(), 2, "Repeated command while cooling down cannot create another summon pair");
                    f.monster.command("hardrush return");
                    f.test.assertTrue(P2LobbyRuntime.channeling(f.monster.player), "Native return command begins a channel");
                    f.monster.move(f.monster.player.position().add(.2, 0, 0));
                })
                .thenWaitUntil(() -> f.await(!P2LobbyRuntime.channeling(f.monster.player), "Actual native movement cancels return"))
                .thenExecute(() -> {
                    f.voluntaryReturnBody = f.monster.player;
                    f.monster.command("hardrush return");
                    f.ageReturnChannel();
                    f.session.onOnline(f.target.profile.id(), f.target.player.getScoreboardName(), true);
                    f.test.assertTrue(f.session.mutationPending(), "The mature return channel competes with a real SQLite write");
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(false), "Completed return channel durably enters lobby"))
                .thenExecute(() -> {
                    f.assertLobby(false);
                    f.test.assertTrue(f.monster.player == f.voluntaryReturnBody,
                            "Successful voluntary return preserves the same native player body instead of accepting an incidental defeat");
                    f.test.assertValueEqual(f.worldSwords(), 1, "The picked-up world sword returns exactly once on leaving the body");
                    f.test.assertValueEqual(f.summons(), 2, "Returning to the lobby keeps both owned summons alive");
                    f.assertSkillCooldowns();
                    f.test.assertTrue(f.monster.participant().monsterCooldownNanos() > 0, "Voluntary return starts another 30-second wait");
                    f.monster.command("hardrush role skeleton");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.SKELETON && !f.session.mutationPending(), "Skeleton selection keeps summon cooldown"))
                .thenExecute(() -> {
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    f.monster.command("hardrush deploy");
                })
                .thenWaitUntil(() -> f.await(f.deployed(2), "Second formal safe deployment"))
                .thenExecute(() -> {
                    f.assertDeployment(2);
                    f.monster.command("hardrush skill");
                    f.monster.command("hardrush skill");
                    f.test.assertTrue(MonsterRoles.status(f.monster.player).contains("arrowsFired=0"), "Pending burst requests do not fire before durable admission");
                })
                .thenWaitUntil(() -> f.awaitSkillEffect(MonsterRoles.status(f.monster.player).contains("arrowsFired=3") && !f.session.mutationPending(), "One admitted burst fires exactly three native arrows"))
                .thenExecute(() -> {
                    f.burstReadyAt = f.monster.participant().monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST);
                    f.test.assertTrue(f.burstReadyAt > System.currentTimeMillis(), "Burst has a persisted epoch deadline");
                    f.monster.command("hardrush skill");
                    f.test.assertTrue(MonsterRoles.status(f.monster.player).contains("arrowsFired=3"), "Cooldown rejects another burst after the first finishes");
                    f.assertSkillCooldowns();
                    f.killMonsterPeer(true);
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(true), "Monster defeat uses native replacement and returns to lobby"))
                .thenExecute(() -> {
                    f.monster.player = f.monster.current();
                    f.assertLobby(true);
                    f.assertSkillCooldowns();
                    f.monster.command("hardrush role creeper");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.CREEPER && !f.session.mutationPending(), "Creeper selection keeps both skill deadlines"))
                .thenExecute(() -> {
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
                    f.assertSkillCooldowns();
                    f.assertStored();
                });
        for (int cycle = 0; cycle < 20; cycle++) {
            final int iteration = cycle;
            final var hunter = cycle % 2 == 0 ? f.monster : f.target;
            final var prey = cycle % 2 == 0 ? f.target : f.monster;
            sequence.thenExecute(() -> {
                        hunter.player = hunter.current(); prey.player = prey.current();
                        prey.move(f.anchor);
                    })
                    .thenExecute(() -> {
                        hunter.command("hardrush role zombie");
                    })
                    .thenWaitUntil(() -> f.await(hunter.participant().monsterRole() == MonsterRole.ZOMBIE && !f.session.mutationPending(), "Cycle role selected " + iteration))
                    .thenExecute(() -> {
                        f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                        hunter.command("hardrush deploy");
                    })
                    .thenWaitUntil(() -> f.await(hunter.participant().role() == ParticipantRole.MONSTER_DEPLOYED
                            && MonsterRoles.isMonster(hunter.player) && !f.session.mutationPending(), "Cycle formal deployment " + iteration))
                    .thenExecute(() -> {
                        hunter.move(prey.player.position().add(1, 0, 0));
                        prey.player.setHealth(1);
                        prey.player.setInvulnerableTime(0);
                        f.test.assertTrue(MonsterRoles.attack(hunter.player, prey.player), "Native zombie melee accepts the legal human");
                        f.test.assertTrue(prey.player.isDeadOrDying(), "Actual native attack creates the lethal event");
                    })
                    .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RESCUE && hunter.participant().revivalEntitled()
                            && prey.current() != null && prey.current().isAlive() && prey.current().level() == P1LobbyWorld.open(f.server).holdingLevel()
                            && f.profiles.isReady(prey.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle all deaths and reward reach rescue atomically " + iteration))
                    .thenExecute(() -> {
                        hunter.command("hardrush return");
                        f.ageReturnChannel(hunter);
                    })
                    .thenWaitUntil(() -> f.await(hunter.participant().role() == ParticipantRole.MONSTER_LOBBY
                            && f.profiles.isReady(hunter.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle return remains usable during rescue " + iteration))
                    .thenExecute(() -> {
                        f.test.assertTrue(hunter.participant().monsterCooldownNanos() > 0, "Revival is tested during the deployment cooldown");
                        if (iteration == 0) {
                            var worlds = f.service("WORLDS", P1RoundWorldBundle.class);
                            var floor = worlds.spawn().below();
                            var original = f.level.getBlockState(floor);
                            try {
                                // Simulate a damaged natural spawn without changing any surrounding terrain.
                                f.level.getChunkAt(floor).setBlockState(floor, Blocks.AIR.defaultBlockState(), 2);
                                f.test.assertFalse(P1RoundWorldBundle.safeStanding(f.level, worlds.spawn()), "The fixture really removed the natural spawn floor");
                                int packetsBefore = hunter.connection.packets.size();
                                hunter.command("hardrush revive");
                                f.test.assertTrue(hunter.participant().revivalEntitled()
                                                && hunter.participant().role() == ParticipantRole.MONSTER_LOBBY
                                                && hunter.connection.packets.subList(packetsBefore, hunter.connection.packets.size()).stream()
                                                    .filter(net.minecraft.network.protocol.game.ClientboundSystemChatPacket.class::isInstance)
                                                    .map(net.minecraft.network.protocol.game.ClientboundSystemChatPacket.class::cast)
                                                    .anyMatch(packet -> packet.content().getString().contains("出生点暂不安全")),
                                        "An unsafe spawn rejects admission and keeps the entitlement");
                            } finally { f.level.getChunkAt(floor).setBlockState(floor, original, 2); }
                        }
                        hunter.openAndClick(Items.TOTEM_OF_UNDYING);
                        hunter.command("hardrush revive");
                    })
                    .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && hunter.participant().role() == ParticipantRole.HUMAN_ACTIVE
                            && hunter.participant().online() && !P1PlayerProfiles.isLocked(hunter.player)
                            && f.profiles.isReady(hunter.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle exactly one revival unlocks an empty human " + iteration))
                    .thenExecute(() -> {
                        f.test.assertFalse(hunter.participant().revivalEntitled(), "Revival consumes the sole entitlement");
                        f.test.assertTrue(hunter.player.getInventory().isEmpty(), "Revival never restores old human gear or role controls");
                        f.test.assertValueEqual(hunter.player.getHealth(), 20f, "Human default health is restored");
                        f.test.assertValueEqual(hunter.player.getFoodData().getFoodLevel(), 20, "Human default food is restored");
                        f.test.assertFalse(P2ProtectionRuntime.protectedEntity(hunter.player), "Revival returns an unprotected human to the natural spawn");
                        f.test.assertValueEqual(hunter.player.getAttribute(Attributes.ARMOR).getBaseValue(), 0d, "Intrinsic monster armor is removed");
                        if (hunter == f.monster) {
                            f.assertSkillCooldowns();
                            f.test.assertValueEqual(hunter.player.getEnderChestInventory().getItem(0).getCount(), 6,
                                    "Every new human life preserves the original same-round ender storage");
                        }
                    });
        }
        sequence.thenExecute(() -> {
                    try (var store = new SqliteRoundStore(f.server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"))) {
                        var first = store.participantRecord(f.round().roundId(), f.monster.profile.id()).orElseThrow();
                        var second = store.participantRecord(f.round().roundId(), f.target.profile.id()).orElseThrow();
                        f.test.assertValueEqual(first.revivalCount(), 10, "Real runtime records ten revivals for the first peer");
                        f.test.assertValueEqual(second.revivalCount(), 10, "Real runtime records ten revivals for the second peer");
                        f.test.assertValueEqual(first.deathCount(), 11, "Initial human death plus ten later native deaths count once");
                        f.test.assertValueEqual(second.deathCount(), 10, "The second peer has ten native human deaths");
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    f.monster.disconnect(); f.target.disconnect();
                    FROZEN_SUMMON_OWNERS.remove(f.monster.profile.id());
                    f.level.getChunkSource().removeTicketWithRadius(LOOT_FIXTURE, f.lootChunk, ARENA_TICKET_RADIUS);
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
        ServerPlayer voluntaryReturnBody;
        AABB humanDrops;
        final ArrayList<ItemEntity> humanDropEntities = new ArrayList<>();
        int lootUnavailableSince = -1;
        AABB worldGearDrops;
        final Map<BlockPos, BlockState> summonRing = new java.util.LinkedHashMap<>();
        final Map<String, Integer> skillWaitStarted = new java.util.HashMap<>();
        long summonReadyAt, burstReadyAt;
        ChunkPos lootChunk;
        String lastStage = "";
        Fixture(GameTestHelper test) {
            this.test = test; server = test.getLevel().getServer();
            FROZEN_SUMMON_OWNERS.add(monster.profile.id());
        }

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
            if (!message.equals(lastStage)) {
                lastStage = message;
                System.out.println("P2 lobby stage: " + message + "; tick=" + server.getTickCount());
            }
            if (!condition && message.equals("Completed return channel durably enters lobby")
                    && monster.participant().role() == ParticipantRole.MONSTER_DEPLOYED
                    && !P2LobbyRuntime.channeling(monster.player) && !session.mutationPending()) {
                throw new IllegalStateException("A mature return channel disappeared before a durable return: "
                        + P1LobbyRuntime.status(server) + ", position=" + monster.player.position()
                        + ", velocity=" + monster.player.getDeltaMovement() + ", body=" + MonsterRoles.status(monster.player));
            }
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + P1LobbyRuntime.status(server));
        }
        void awaitSkillEffect(boolean condition, String message) {
            if (condition) { skillWaitStarted.remove(message); return; }
            int started = skillWaitStarted.computeIfAbsent(message, ignored -> server.getTickCount());
            if (server.getTickCount() - started >= 1000) {
                throw new IllegalStateException(message + " exceeded 1000 native ticks: " + P1LobbyRuntime.status(server)
                        + ", mutationPending=" + session.mutationPending() + ", casting=" + P2MonsterRuntime.casting(monster.player)
                        + ", arenaTicking=" + arenaTicking() + ", summonCount=" + summons()
                        + ", body=" + MonsterRoles.status(monster.player));
            }
            await(false, message);
        }
        boolean humansReady() {
            return round().phase() == RoundPhase.RUNNING && !session.mutationPending()
                    && monster.participant().online() && target.participant().online()
                    && !P1PlayerProfiles.isLocked(monster.player) && !P1PlayerProfiles.isLocked(target.player);
        }
        void prepareArena() {
            var worlds = service("WORLDS", P1RoundWorldBundle.class);
            level = worlds.level(Level.OVERWORLD);
            // This suite measures identity/IO lifecycle; sunlight and autonomous summon combat have their own native tests.
            // The dark roof must not let an unrelated naturally spawned zombie replace the voluntary-return assertion with a defeat.
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            var daylight = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                    .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
            level.clockManager().setTotalTicks(daylight, 18000);
            level.clockManager().setPaused(daylight, true);
            BlockPos center = new BlockPos(worlds.spawn().getX() + 160, 220, worlds.spawn().getZ());
            anchor = Vec3.atBottomCenterOf(center);
            lootChunk = new ChunkPos(center.getX() >> 4, center.getZ() >> 4);
            // Full status radius seven leaves an entity-ticking radius five, covering the 135x135 arena.
            level.getChunkSource().addTicketWithRadius(LOOT_FIXTURE, lootChunk, ARENA_TICKET_RADIUS);
            // Fixture setup alone loads chunks and creates terrain. The production search cannot do either.
            for (int x = -67; x <= 67; x++) for (int z = -67; z <= 67; z++) {
                BlockPos feet = center.offset(x, 0, z);
                level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 3; y++) level.setBlock(feet.above(y), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(feet.above(3), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        boolean arenaTicking() {
            var center = BlockPos.containing(anchor);
            for (int dx : new int[]{-67, 0, 67}) for (int dz : new int[]{-67, 0, 67}) {
                if (!level.isPositionEntityTicking(center.offset(dx, 0, dz))) return false;
            }
            return true;
        }
        void killMonsterPeer(boolean alreadyMonster) {
            dead = monster.player;
            if (!alreadyMonster) test.assertTrue(level.isPositionEntityTicking(dead.blockPosition()),
                    "Immediate death-drop assertion requires the native loot arena to remain entity ticking");
            test.assertTrue(dead.hurtServer(level, dead.damageSources().genericKill(), Float.MAX_VALUE), "Native damage outside protection kills this body");
            test.assertTrue(dead.isDeadOrDying() && P1PlayerProfiles.isLocked(dead), "Production death hook immediately holds the dead body");
            if (!alreadyMonster) {
                humanDropEntities.addAll(level.getEntitiesOfClass(ItemEntity.class, humanDrops).stream()
                        .filter(item -> item.getItem().is(Items.DIAMOND)).toList());
                test.assertValueEqual(diamonds(), 3, "Native human death drops the original inventory exactly once");
            }
            dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            test.assertTrue(monster.current() == dead && dead.connection.player == dead && dead.isDeadOrDying(), "Early native respawn cannot bypass SQLite identity commit");
        }
        boolean monsterReady(boolean replacementRequired) {
            var current = monster.current();
            return monster.participant().role() == ParticipantRole.MONSTER_LOBBY && current != null
                    && (!replacementRequired || current != dead) && current.isAlive() && current.level() == P1LobbyWorld.open(server).holdingLevel()
                    && profiles.isReady(monster.profile.id(), round()) && !session.mutationPending() && originalLootVisible();
        }
        boolean deployed(int sequence) {
            var p = monster.participant();
            return p.role() == ParticipantRole.MONSTER_DEPLOYED && p.monsterDeploymentSequence() == sequence
                    && monster.player.level() == level && !P1PlayerProfiles.isLocked(monster.player)
                    && MonsterRoles.isMonster(monster.player) && !session.mutationPending()
                    && arenaTicking() && level.isPositionEntityTicking(monster.player.blockPosition()) && originalLootVisible();
        }
        void assertDeployment(int sequence) {
            test.assertValueEqual(monster.player.gameMode.getGameModeForPlayer(), net.minecraft.world.level.GameType.SURVIVAL,
                    "A successful monster sortie restores survival mode");
            test.assertValueEqual(monster.participant().monsterDeploymentSequence(), sequence, "Exactly one persisted sortie is admitted");
            double horizontal = monster.player.position().subtract(target.player.position()).horizontalDistance();
            test.assertTrue(horizontal >= 32 && horizontal <= 64, "Actual native monster spawns in the target's 32-64 block ring");
            test.assertTrue(monster.player.position().distanceToSqr(target.player.position()) >= 24 * 24, "Actual body respects the human separation");
            test.assertFalse(P2ProtectionRuntime.protectedEntity(monster.player), "Actual monster never enters spawn protection");
            var kind = monster.participant().monsterRole();
            test.assertValueEqual(MonsterRoles.nativeModel(monster.player).getType(), switch (kind) {
                case ZOMBIE -> EntityTypes.ZOMBIE;
                case SKELETON -> EntityTypes.SKELETON;
                case CREEPER -> EntityTypes.CREEPER;
            }, "Persisted role choice drives the real native body");
            String key = kind.name().toLowerCase(java.util.Locale.ROOT);
            var values = round().rules().combatValues();
            test.assertValueEqual((double)monster.player.getMaxHealth(), values.get(key + ".maxHealth"), "Formal health comes from the immutable round rules");
            double movement = values.get(key + ".movementAttribute");
            test.assertTrue(Math.abs(monster.player.getAttributeValue(Attributes.MOVEMENT_SPEED) - movement * movement) < .00001,
                    "Formal movement translates the saved native attribute to player input");
            test.assertValueEqual(monster.player.getAttribute(Attributes.ARMOR).getBaseValue(), values.get(key + ".armor"), "Formal intrinsic armor uses the round snapshot");
            test.assertValueEqual(monster.player.getFoodData().getFoodLevel(), 6, "The role body has no hunger or player sprint input");
            test.assertTrue(monster.player.getInventory().getItem(kind == MonsterRole.CREEPER ? 0 : 1).is(switch (kind) {
                case ZOMBIE -> Items.ROTTEN_FLESH;
                case SKELETON -> Items.BONE;
                case CREEPER -> Items.GUNPOWDER;
            }), "Only the selected role's fixed skill control is equipped");
            test.assertValueEqual(diamonds(), 3, "Outings do not duplicate or pick up the old human inventory");
        }
        void assertMenuDoesNotUnlockRoleDrops() {
            int skillSlot = monster.participant().monsterRole() == MonsterRole.CREEPER ? 0 : 1;
            monster.player.getInventory().setSelectedSlot(skillSlot);
            Item control = monster.player.getInventory().getItem(skillSlot).getItem();
            monster.command("hardrush menu");
            test.assertTrue(dev.hardrush.p2.lobby.P2LobbyUi.isMenu(monster.player), "Outside monster can open the return menu");
            var bounds = monster.player.getBoundingBox().inflate(5);
            int before = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(control)).mapToInt(ItemStack::getCount).sum();
            monster.player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, monster.player.blockPosition().below(), Direction.UP, 0));
            int after = level.getEntitiesOfClass(ItemEntity.class, bounds).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(control)).mapToInt(ItemStack::getCount).sum();
            test.assertValueEqual(after, before, "Lobby GUI click exemption never unlocks raw fixed-role drop packets");
            test.assertTrue(monster.player.getInventory().getItem(skillSlot).is(control), "The fixed skill control remains equipped while the menu is open");
            monster.player.closeContainer();
        }
        void equipWorldSword() {
            worldGearDrops = new AABB(monster.player.blockPosition()).inflate(6);
            var sword = new ItemEntity(level, monster.player.getX(), monster.player.getY(), monster.player.getZ(), new ItemStack(Items.DIAMOND_SWORD));
            sword.setNoPickUpDelay(); level.addFreshEntity(sword); sword.playerTouch(monster.player);
            test.assertTrue(sword.isRemoved(), "A formal zombie automatically equips the world weapon through the native pickup hook");
            test.assertTrue(MonsterRoles.actualEquipment(monster.player, EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), "Actual species gear remains separate from the selected skill slot");
            test.assertValueEqual(worldSwords(), 0, "Exactly one sword transfers from the world into the monster body");
        }
        void blockSummonRing() {
            var center = monster.player.blockPosition();
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < 4 || distanceSquared > 16) continue;
                var feet = center.offset(dx, 0, dz);
                summonRing.put(feet, level.getBlockState(feet));
                test.assertTrue(level.setBlock(feet, Blocks.SMOOTH_STONE.defaultBlockState(), 2),
                        "The fixture blocks every prepared summon foot position before the commit callback");
            }
        }
        void restoreSummonRing() {
            summonRing.forEach((position, state) -> level.setBlock(position, state, 2));
            summonRing.clear();
        }
        int worldSwords() {
            return level.getEntitiesOfClass(ItemEntity.class, worldGearDrops).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND_SWORD)).mapToInt(ItemStack::getCount).sum();
        }
        int summons() {
            return level.getEntitiesOfClass(Zombie.class, new AABB(BlockPos.containing(anchor)).inflate(150)).stream()
                    .filter(child -> monster.profile.id().equals(MonsterRoles.summonOwner(child))).mapToInt(child -> {
                        test.assertTrue(child.isBaby(), "Formal summon retains the native baby body");
                        return 1;
                    }).sum();
        }
        void assertSkillCooldowns() {
            var cooldowns = monster.participant().monsterSkillReadyAtMillis();
            if (summonReadyAt != 0) test.assertValueEqual(cooldowns.get(MonsterSkill.ZOMBIE_SUMMON), summonReadyAt, "Summon deadline survives role changes, defeat, lobby and reconnect");
            if (burstReadyAt != 0) test.assertValueEqual(cooldowns.get(MonsterSkill.SKELETON_BURST), burstReadyAt, "Burst deadline survives role changes, defeat, lobby and reconnect");
        }
        void assertLobby(boolean replaced) {
            monster.player = monster.current();
            test.assertTrue(monster.player != null && monster.player.isAlive() && monster.player.level() == P1LobbyWorld.open(server).holdingLevel()
                    && P1PlayerProfiles.isLocked(monster.player), "The authoritative native body is isolated in the real lobby");
            test.assertTrue(P1PlayerProfiles.canMoveInLobby(monster.player), "Verified monster standby may move without competition admission");
            test.assertValueEqual(monster.player.gameMode.getGameModeForPlayer(), net.minecraft.world.level.GameType.ADVENTURE,
                    "Every returned monster body uses adventure mode");
            if (replaced) test.assertTrue(monster.player != dead, "Defeat must create a native replacement rather than heal the dead object");
            test.assertTrue(monster.player.connection.player == monster.player, "The native connection rebinds to the live lobby body");
            test.assertTrue(monster.player.getInventory().isEmpty(), "No role controls or human death inventory enter the lobby");
            test.assertFalse(MonsterRoles.isMonster(monster.player), "Lobby has no active prototype role body");
            test.assertValueEqual(monster.player.getEnderChestInventory().getItem(0).getCount(), 6, "Same-round ender storage survives every sortie and native respawn");
            test.assertValueEqual(monster.participant().lifeSequence(), 1, "Monster outings never create a new human life");
            test.assertValueEqual(diamonds(), 3, "Exactly one human death drop set remains");
        }
        void ageReturnChannel() { ageReturnChannel(monster); }
        void ageReturnChannel(Peer peer) {
            try {
                Object binding = binding();
                var channelsField = binding.getClass().getDeclaredField("channels"); channelsField.setAccessible(true);
                @SuppressWarnings("unchecked") var channels = (Map<UUID, Object>) channelsField.get(binding);
                Object old = channels.get(peer.profile.id());
                test.assertTrue(old != null, "The production command created a channel before fixture clock adjustment");
                var constructor = old.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
                var p = peer.participant();
                channels.put(peer.profile.id(), constructor.newInstance(peer.player, peer.player.level(), peer.player.position(),
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
            // Native loot can drift beyond the original five-block death-query box.
            // Count the whole private arena; UUID visibility separately proves the original drops survive.
            int count = level.getEntitiesOfClass(ItemEntity.class, humanDrops.inflate(128)).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
            if (count != 3 && !humanDropEntities.isEmpty()) {
                System.out.println("P2 loot conservation diagnostics: " + lootDiagnostic());
            }
            return count;
        }
        /** Entity section reload is asynchronous after a native player changes dimension. Counts are asserted separately. */
        boolean originalLootVisible() {
            if (humanDropEntities.isEmpty()) return true;
            boolean visible = level.isPositionEntityTicking(BlockPos.containing(humanDrops.getCenter()))
                    && humanDropEntities.stream().allMatch(original -> level.getEntity(original.getUUID()) instanceof ItemEntity);
            if (visible) { lootUnavailableSince = -1; return true; }
            if (lootUnavailableSince < 0) {
                lootUnavailableSince = server.getTickCount();
                System.out.println("P2 waiting for original loot section visibility: " + lootDiagnostic());
            }
            if (server.getTickCount() - lootUnavailableSince >= 200) {
                throw new IllegalStateException("Original death loot UUIDs must become visible in an entity-ticking chunk within 200 ticks: "
                        + lootDiagnostic() + ", runtime=" + P1LobbyRuntime.status(server));
            }
            return false;
        }
        String lootDiagnostic() {
            return "sortie=" + monster.participant().monsterDeploymentSequence()
                    + ", bounds=" + humanDrops + ", ticking=" + level.isPositionEntityTicking(BlockPos.containing(humanDrops.getCenter()))
                    + ", chunk=" + lootChunk + ", entities=" + humanDropEntities.stream().map(item ->
                        "id=" + item.getUUID() + ",position=" + item.position() + ",removed=" + item.getRemovalReason()
                                + ",stack=" + item.getItem() + ",lookup=" + level.getEntity(item.getUUID())).toList();
        }
        void assertStored() {
            try (var store = new SqliteRoundStore(server.getWorldPath(LevelResource.ROOT).resolve("hardrush/p1.sqlite"))) {
                var saved = RoundSnapshotCodec.decode(store.load(round().roundId()).orElseThrow().payload()).participants().get(monster.profile.id());
                test.assertValueEqual(saved.role(), ParticipantRole.MONSTER_LOBBY, "Real SQLite stores the returned lobby identity");
                test.assertValueEqual(saved.monsterRole(), MonsterRole.CREEPER, "Real SQLite retains role choice across reconnect");
                test.assertValueEqual(saved.monsterDimension(), MonsterDimension.OVERWORLD, "Real SQLite retains dimension choice across reconnect");
                test.assertValueEqual(saved.monsterDeploymentSequence(), 3, "Real SQLite retains sortie label across reconnect");
                test.assertValueEqual(saved.lifeSequence(), 1, "Real SQLite never revives the human through a monster respawn");
                test.assertValueEqual(saved.monsterSkillReadyAtMillis().get(MonsterSkill.ZOMBIE_SUMMON), summonReadyAt, "Real SQLite retains the original summon deadline");
                test.assertValueEqual(saved.monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST), burstReadyAt, "Real SQLite retains the original burst deadline");
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
                            test.assertTrue(player.level() == P1LobbyWorld.open(server).holdingLevel() && P1PlayerProfiles.isLocked(player), "Native login starts in real profile quarantine");
                        }).thenWaitUntil(() -> await(profiles.isReady(profile.id(), round()), "Real profile journal ready: " + profile.name()));
            }
            void command(String command) {
                if (P1SpatialFixtures.handle(player, command)) return;
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
