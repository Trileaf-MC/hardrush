package dev.hardrush.gametest;

import dev.hardrush.HardRushProperties;
import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.lobby.LobbyRuntime;
import dev.hardrush.lobby.RevivalTotems;
import dev.hardrush.locator.PlayerLocator;
import dev.hardrush.monster.MonsterRoundRuntime;
import dev.hardrush.protection.ProtectionRuntime;
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
public final class LobbyRuntimeGameTest {
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
        if (!Boolean.getBoolean(TestProperties.LOBBY_RUNTIME)) { test.succeed(); return; }
        test.assertTrue(HardRushProperties.competitionEnabled(), "The dedicated run requires the production runtime");
        var f = new Fixture(test);
        test.onEachTick(() -> {
            if (f.clock != null) f.clock.accumulateAndGet(System.nanoTime(), Math::max);
            f.monster.acknowledge(); f.target.acknowledge();
            test.assertFalse(RoundRuntime.sessionState(f.server).filter(s -> s == RoundSession.State.FAILED).isPresent(),
                    "Production runtime failed: " + RoundRuntime.status(f.server));
        });
        var sequence = test.startSequence()
                .thenWaitUntil(() -> f.await(RoundRuntime.sessionState(f.server).filter(s -> s == RoundSession.State.OPEN).isPresent(), "Runtime bootstrap"))
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
                    f.test.assertFalse(ProtectionRuntime.protectedEntity(f.target.player), "A real active human is outside protection");
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
                    HuntInputFixtures.role(f.monster.player, "zombie");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.ZOMBIE && !f.session.mutationPending(), "Role choice durably saved"))
                .thenExecute(f::buyAdultEggFromWitch)
                .thenWaitUntil(() -> f.await(f.monster.participant().wallet().ordered(dev.hardrush.core.round.ShopOffer.ADULT_EGG) == 1
                        && !f.session.mutationPending(), "The witch purchase commits durably"))
                .thenExecute(() -> {
                    f.assertWitchPurchase();
                    HuntInputFixtures.hunt(f.monster.player, f.target.profile.id(), f.monster.connection.packets);
                    f.test.assertValueEqual(f.monster.participant().role(), ParticipantRole.MONSTER_LOBBY, "Early deploy cannot bypass the first 30 seconds");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    HuntInputFixtures.hunt(f.monster.player, f.target.profile.id(), f.monster.connection.packets);
                })
                .thenWaitUntil(() -> f.await(f.deployed(1), "First formal safe deployment"))
                .thenExecute(() -> {
                    f.assertDeployment(1);
                    f.test.assertValueEqual(f.monster.player.getInventory().getItem(2).getCount(), 2, "The bought adult egg joins the role egg");
                    f.assertMenuDoesNotUnlockRoleDrops();
                    f.equipWorldSword();
                    f.test.assertFalse(MonsterRoles.summon(f.monster.player), "Historical summon action remains retired");
                    f.spawnEgg(1, 2);
                    f.spawnEgg(2, -2);
                    f.test.assertValueEqual(f.summons(), 2, "The formal outing owns one adult and one baby from its eggs");
                    f.test.assertFalse(f.monster.participant().monsterSkillReadyAtMillis().containsKey(MonsterSkill.ZOMBIE_SUMMON), "Egg use does not create retired cooldown state");
                    f.monster.command("hardrush skill");
                    f.test.assertValueEqual(f.summons(), 2, "Retired skill command cannot add companions");
                    f.monster.command("hardrush return");
                    f.test.assertTrue(LobbyRuntime.channeling(f.monster.player), "Native return command begins a channel");
                    f.monster.move(f.monster.player.position().add(.2, 0, 0));
                })
                .thenWaitUntil(() -> f.await(!LobbyRuntime.channeling(f.monster.player), "Actual native movement cancels return"))
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
                    f.test.assertValueEqual(f.summons(), 0, "Returning to the lobby dismisses the entire owned lineage");
                    f.assertSkillCooldowns();
                    f.test.assertTrue(f.monster.participant().monsterCooldownNanos() > 0, "Voluntary return starts another 30-second wait");
                    HuntInputFixtures.role(f.monster.player, "skeleton");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.SKELETON && !f.session.mutationPending(), "Skeleton selection keeps summon cooldown"))
                .thenExecute(() -> {
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    HuntInputFixtures.hunt(f.monster.player, f.target.profile.id(), f.monster.connection.packets);
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
                    f.test.assertValueEqual(f.monster.participant().wallet().coins(), f.coinsBeforeDefeat + 5,
                            "A native fall death after recent player damage pays the monster exactly five coins");
                    f.assertWorthReward();
                    f.assertSkillCooldowns();
                    HuntInputFixtures.role(f.monster.player, "creeper");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().monsterRole() == MonsterRole.CREEPER && !f.session.mutationPending(), "Creeper selection keeps both skill deadlines"))
                .thenExecute(() -> {
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                    HuntInputFixtures.hunt(f.monster.player, f.target.profile.id(), f.monster.connection.packets);
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
                        HuntInputFixtures.role(hunter.player, "zombie");
                    })
                    .thenWaitUntil(() -> f.await(hunter.participant().monsterRole() == MonsterRole.ZOMBIE && !f.session.mutationPending(), "Cycle role selected " + iteration))
                    .thenExecute(() -> {
                        f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
                        HuntInputFixtures.hunt(hunter.player, prey.profile.id(), hunter.connection.packets);
                    })
                    .thenWaitUntil(() -> f.await(hunter.participant().role() == ParticipantRole.MONSTER_DEPLOYED
                            && MonsterRoles.isMonster(hunter.player) && !f.session.mutationPending(), "Cycle formal deployment " + iteration))
                    .thenExecute(() -> {
                        if (iteration == 0) f.allowCombat();
                        hunter.move(prey.player.position().add(1, 0, 0));
                        prey.player.setHealth(1);
                        prey.player.setInvulnerableTime(0);
                        // Random birth gear may be a spear, whose native STAB input has its own suite.
                        hunter.player.getInventory().setItem(8, ItemStack.EMPTY);
                        hunter.player.getInventory().setSelectedSlot(8);
                        MonsterRoles.repairLoadout(hunter.player);
                        boolean landed = MonsterRoles.attack(hunter.player, prey.player);
                        f.test.assertTrue(landed, "Native zombie melee accepts the legal human: cycle=" + iteration
                                + ", role=" + hunter.participant().role() + ", phase=" + f.round().phase()
                                + ", targetHp=" + prey.player.getHealth() + ", targetRole=" + prey.participant().role()
                                + ", canAct=" + MonsterRoundRuntime.canAct(hunter.player)
                                + ", legal=" + MonsterRoles.legalTarget(hunter.profile.id(), f.level, prey.player)
                                + ", locked=" + PlayerProfiles.isLocked(prey.player)
                                + ", protected=" + ProtectionRuntime.protectedEntity(prey.player)
                                + ", invulnerable=" + prey.player.getAbilities().invulnerable
                                + ", distance=" + hunter.player.distanceTo(prey.player)
                                + ", los=" + hunter.player.hasLineOfSight(prey.player)
                                + ", pact=" + dev.hardrush.pact.DawnPactRuntime.reject(hunter.player, prey.player)
                                + ", sourceRejected=" + dev.hardrush.combat.MonsterCombatRuntime.reject(MonsterRoles.nativeModel(hunter.player), prey.player)
                                + ", actor=" + MonsterRoles.status(hunter.player));
                        f.test.assertTrue(prey.player.isDeadOrDying(), "Actual native attack creates the lethal event");
                    })
                    .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RESCUE && hunter.participant().revivalEntitled()
                            && prey.current() != null && prey.current().isAlive() && prey.current().level() == LobbyWorld.open(f.server).holdingLevel()
                            && f.profiles.isReady(prey.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle all deaths and reward reach rescue atomically " + iteration))
                    // The kill's revival recalls the hunter in the same durable batch; it goes to the altar next.
                    .thenWaitUntil(() -> f.await(hunter.participant().role() == ParticipantRole.MONSTER_LOBBY
                            && f.profiles.isReady(hunter.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle kill recalls the hunter during rescue " + iteration))
                    .thenWaitUntil(() -> f.await(iteration != 0 || heldTotems(hunter.player) == hunter.participant().revivalCharges(),
                            "Camp inventory projects the revival balance as totems"))
                    .thenExecute(() -> {
                        if (iteration != 0) return;
                        var held = java.util.stream.IntStream.range(0, 9).mapToObj(hunter.player.getInventory()::getItem)
                                .filter(RevivalTotems::is).findFirst().orElseThrow();
                        f.test.assertValueEqual(held.getHoverName().getString(), "复生图腾", "The totem explains itself by name");
                        f.test.assertFalse(held.has(net.minecraft.core.component.DataComponents.DEATH_PROTECTION), "A revival totem never guards against death");
                        f.test.assertValueEqual(held.getMaxStackSize(), RevivalTotems.MAX_STACK, "Charges stack in one native slot");
                        throwTotem(hunter.player);
                    })
                    .thenWaitUntil(() -> f.await(iteration != 0 || hunter.participant().revivalCharges() == 0
                            && thrownTotem(hunter.player) != null && heldTotems(hunter.player) == 0 && !f.session.mutationPending(),
                            "A thrown totem commits its charge before it lands"))
                    .thenExecute(() -> {
                        if (iteration != 0) return;
                        var floor = thrownTotem(hunter.player);
                        floor.playerTouch(prey.current());
                        f.test.assertFalse(floor.isRemoved() || f.session.mutationPending(), "Native pickup delay still applies");
                        floor.setPickUpDelay(0);
                        floor.playerTouch(prey.current());
                    })
                    .thenWaitUntil(() -> f.await(iteration != 0 || prey.participant().revivalCharges() == 1
                            && thrownTotem(hunter.player) == null && heldTotems(prey.current()) == 1 && !f.session.mutationPending(),
                            "Another camp monster picks up the transferred charge"))
                    .thenExecute(() -> { if (iteration == 0) throwTotem(prey.current()); })
                    .thenWaitUntil(() -> f.await(iteration != 0 || prey.participant().revivalCharges() == 0
                            && thrownTotem(prey.current()) != null && !f.session.mutationPending(), "The receiver can pass it back"))
                    .thenExecute(() -> {
                        if (iteration != 0) return;
                        var floor = thrownTotem(prey.current());
                        floor.setPickUpDelay(0);
                        floor.playerTouch(hunter.player);
                    })
                    .thenWaitUntil(() -> f.await(iteration != 0 || hunter.participant().revivalCharges() == 1
                            && heldTotems(hunter.player) == 1 && !f.session.mutationPending(), "The original owner regains the charge"))
                    .thenExecute(() -> {
                        f.test.assertTrue(hunter.participant().monsterCooldownNanos() > 0, "Revival is tested during the deployment cooldown");
                        if (iteration == 0) {
                            var worlds = f.service("WORLDS", RoundWorldBundle.class);
                            var floor = worlds.spawn().below();
                            var original = f.level.getBlockState(floor);
                            try {
                                // Simulate a damaged natural spawn without changing any surrounding terrain.
                                f.level.getChunkAt(floor).setBlockState(floor, Blocks.AIR.defaultBlockState(), 2);
                                f.test.assertFalse(RoundWorldBundle.safeStanding(f.level, worlds.spawn()), "The fixture really removed the natural spawn floor");
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
                            && hunter.participant().online() && !PlayerProfiles.isLocked(hunter.player)
                            && f.profiles.isReady(hunter.profile.id(), f.round()) && !f.session.mutationPending(), "Cycle exactly one revival unlocks an empty human " + iteration))
                    .thenExecute(() -> {
                        f.test.assertFalse(hunter.participant().revivalEntitled(), "Revival consumes the sole entitlement");
                        f.test.assertTrue(hunter.player.getInventory().isEmpty(), "Revival never restores old human gear or role controls");
                        f.test.assertValueEqual(hunter.player.getHealth(), 20f, "Human default health is restored");
                        f.test.assertValueEqual(hunter.player.getFoodData().getFoodLevel(), 20, "Human default food is restored");
                        f.test.assertFalse(ProtectionRuntime.protectedEntity(hunter.player), "Revival returns an unprotected human to the natural spawn");
                        f.test.assertValueEqual(hunter.player.getAttribute(Attributes.ARMOR).getBaseValue(), 0d, "Intrinsic monster armor is removed");
                        f.test.assertFalse(PlayerLocator.hideFrom(hunter.player, f.target.player),
                                "A committed human revival restores locator visibility");
                        if (hunter == f.monster) {
                            f.assertSkillCooldowns();
                            f.test.assertValueEqual(hunter.player.getEnderChestInventory().getItem(0).getCount(), 6,
                                    "Every new human life preserves the original same-round ender storage");
                        }
                    });
        }
        sequence.thenExecute(() -> {
                    try (var store = new SqliteRoundStore(f.server.getWorldPath(LevelResource.ROOT).resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE))) {
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

    static final class Fixture {
        final GameTestHelper test;
        final MinecraftServer server;
        final Peer monster = new Peer("LobbyMonster"), target = new Peer("LobbyTarget");
        RoundSession session;
        PlayerProfiles profiles;
        AtomicLong clock;
        ServerLevel level;
        Vec3 anchor;
        ServerPlayer dead;
        int coinsBeforeDefeat;
        int worthBeforeDefeat;
        Vec3 defeatPosition;
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
            session = service("SESSIONS", RoundSession.class);
            profiles = service("PROFILES", PlayerProfiles.class);
            test.assertValueEqual(round().sequence(), 1L, "Dedicated storage starts at its first round");
            test.assertValueEqual(round().rules().mode(), GameMode.HARDCORE, "Actual runtime mode is hardcore");
            clock = new AtomicLong(System.nanoTime());
            try {
                var field = RoundSession.class.getDeclaredField("clock"); field.setAccessible(true);
                field.set(session, (MonotonicClock) clock::get);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        RoundSnapshot round() { return RoundRuntime.snapshot(server).orElseThrow(); }
        void await(boolean condition, String message) {
            if (!message.equals(lastStage)) {
                lastStage = message;
                System.out.println("Lobby runtime stage: " + message + "; tick=" + server.getTickCount());
            }
            if (!condition && message.equals("Completed return channel durably enters lobby")
                    && monster.participant().role() == ParticipantRole.MONSTER_DEPLOYED
                    && !LobbyRuntime.channeling(monster.player) && !session.mutationPending()) {
                throw new IllegalStateException("A mature return channel disappeared before a durable return: "
                        + RoundRuntime.status(server) + ", position=" + monster.player.position()
                        + ", velocity=" + monster.player.getDeltaMovement() + ", body=" + MonsterRoles.status(monster.player));
            }
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            test.assertTrue(condition, message + ": " + RoundRuntime.status(server));
        }
        void awaitSkillEffect(boolean condition, String message) {
            if (condition) { skillWaitStarted.remove(message); return; }
            int started = skillWaitStarted.computeIfAbsent(message, ignored -> server.getTickCount());
            if (server.getTickCount() - started >= 1000) {
                throw new IllegalStateException(message + " exceeded 1000 native ticks: " + RoundRuntime.status(server)
                        + ", mutationPending=" + session.mutationPending() + ", casting=" + MonsterRoundRuntime.casting(monster.player)
                        + ", arenaTicking=" + arenaTicking() + ", summonCount=" + summons()
                        + ", body=" + MonsterRoles.status(monster.player));
            }
            await(false, message);
        }
        boolean humansReady() {
            return round().phase() == RoundPhase.RUNNING && !session.mutationPending()
                    && monster.participant().online() && target.participant().online()
                    && !PlayerProfiles.isLocked(monster.player) && !PlayerProfiles.isLocked(target.player);
        }
        void prepareArena() {
            var worlds = service("WORLDS", RoundWorldBundle.class);
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
        void allowCombat() {
            // Combat starts after the first sunrise; do not bypass the production pact gate.
            dev.hardrush.pact.DawnPactRuntime.tick(server);
            var daylight = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                    .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
            level.clockManager().setTotalTicks(daylight, dev.hardrush.core.combat.DawnPact.SUNRISE_TICKS + 18_000);
            dev.hardrush.pact.DawnPactRuntime.tick(server);
        }
        void killMonsterPeer(boolean alreadyMonster) {
            dead = monster.player;
            if (!alreadyMonster) test.assertTrue(level.isPositionEntityTicking(dead.blockPosition()),
                    "Immediate death-drop assertion requires the native loot arena to remain entity ticking");
            if (alreadyMonster) {
                allowCombat();
                coinsBeforeDefeat = monster.participant().wallet().coins();
                worthBeforeDefeat = dev.hardrush.monster.MonsterRoundRuntime.worth(dead).orElseThrow();
                test.assertTrue(worthBeforeDefeat >= 100, "A deployed body is worth at least the base experience");
                test.assertValueEqual(dead.totalExperience, worthBeforeDefeat, "The monster's experience bar shows its worth");
                test.assertValueEqual(dead.experienceLevel, dev.hardrush.core.round.MonsterWorth.level(worthBeforeDefeat),
                        "The bar's level follows the vanilla experience curve");
                dead.setInvulnerableTime(0);
                dead.damageCooldownTime = 0;
                test.assertTrue(dead.hurtServer(level, dead.damageSources().playerAttack(target.player), 1),
                        "The human lands a nonlethal native attack");
                test.assertTrue(dead.isAlive() && dead.getLastHurtByPlayer() == target.player,
                        "Vanilla remembers the player before the subsequent environmental death");
                dead.damageCooldownTime = 0;
            }
            var source = alreadyMonster ? dead.damageSources().fall() : dead.damageSources().genericKill();
            defeatPosition = dead.position();
            test.assertTrue(dead.hurtServer(level, source, Float.MAX_VALUE), "Native damage outside protection kills this body");
            test.assertTrue(dead.isDeadOrDying() && PlayerProfiles.isLocked(dead), "Production death hook immediately holds the dead body");
            if (!alreadyMonster) {
                humanDropEntities.addAll(level.getEntitiesOfClass(ItemEntity.class, humanDrops).stream()
                        .filter(item -> item.getItem().is(Items.DIAMOND)).toList());
                test.assertValueEqual(diamonds(), 3, "Native human death drops the original inventory exactly once");
            }
            dead.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            test.assertTrue(monster.current() == dead && dead.connection.player == dead && dead.isDeadOrDying(), "Early native respawn cannot bypass SQLite identity commit");
        }
        /** The fall death after a human's hit sprays the worth; only a worth of level 10 adds the signed book. */
        void assertWorthReward() {
            var around = new AABB(defeatPosition, defeatPosition).inflate(8);
            test.assertFalse(level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, around).isEmpty(),
                    "The slain monster's worth starts spraying as experience orbs");
            long books = level.getEntitiesOfClass(ItemEntity.class, around).stream()
                    .filter(item -> item.getItem().is(Items.ENCHANTED_BOOK)
                            && item.getItem().getHoverName().getString().equals("从 LobbyMonster 身上找到的书")).count();
            boolean worthy = dev.hardrush.core.round.MonsterWorth.bookCost(round().rules(), worthBeforeDefeat) > 0;
            test.assertValueEqual(books, worthy ? 1L : 0L, "Exactly one signed book drops, and only from a worthy body");
        }
        boolean monsterReady(boolean replacementRequired) {
            var current = monster.current();
            return monster.participant().role() == ParticipantRole.MONSTER_LOBBY && current != null
                    && (!replacementRequired || current != dead) && current.isAlive() && current.level() == LobbyWorld.open(server).holdingLevel()
                    && profiles.isReady(monster.profile.id(), round()) && !session.mutationPending() && originalLootVisible();
        }
        boolean deployed(int sequence) {
            var p = monster.participant();
            return p.role() == ParticipantRole.MONSTER_DEPLOYED && p.monsterDeploymentSequence() == sequence
                    && monster.player.level() == level && !PlayerProfiles.isLocked(monster.player)
                    && MonsterRoles.isMonster(monster.player) && !session.mutationPending()
                    && arenaTicking() && level.isPositionEntityTicking(monster.player.blockPosition()) && originalLootVisible();
        }
        void assertDeployment(int sequence) {
            test.assertTrue(PlayerLocator.hideFrom(monster.player, target.player), "Formal deployed monster is hidden from a human locator");
            test.assertFalse(PlayerLocator.hideFrom(target.player, monster.player), "Formal monster still receives human locations");
            test.assertValueEqual(monster.player.gameMode.getGameModeForPlayer(), net.minecraft.world.level.GameType.ADVENTURE,
                    "A successful monster sortie restricts block editing through adventure mode");
            test.assertValueEqual(monster.participant().monsterDeploymentSequence(), sequence, "Exactly one persisted sortie is admitted");
            double horizontal = monster.player.position().subtract(target.player.position()).horizontalDistance();
            test.assertTrue(horizontal >= 24 && horizontal <= 40, "Actual native monster spawns in the target's 24-40 block ring");
            test.assertTrue(monster.player.position().distanceToSqr(target.player.position()) >= 16 * 16, "Actual body respects the human separation");
            test.assertFalse(ProtectionRuntime.protectedEntity(monster.player), "Actual monster never enters spawn protection");
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
                case ZOMBIE -> Items.ZOMBIE_SPAWN_EGG;
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
            test.assertTrue(dev.hardrush.lobby.LobbyMenus.isMenu(monster.player), "Outside monster can open the return menu");
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
        void buyAdultEggFromWitch() {
            var wallet = monster.participant().wallet();
            test.assertTrue(wallet.coins() == 6 && wallet.roleChosen(), "The first transformation gives the starter coins and the role is chosen");
            dev.hardrush.lobby.CampKit.tick(server);
            test.assertValueEqual(coins(), 6, "The camp projects the coin balance as native emeralds");
            var lobby = LobbyWorld.open(server);
            HuntInputFixtures.move(monster.player, lobby.layoutPosition(dev.hardrush.core.lobby.LobbyLayout.facility(
                    dev.hardrush.core.lobby.LobbyLayout.Facility.MERCHANT)).add(0, 0, 2));
            dev.hardrush.lobby.LobbyInteractions.tick(server);
            var ids = dev.hardrush.lobby.LobbyInteractions.get(server).orElseThrow().roleModels().view(monster.player);
            HuntInputFixtures.click(monster.player, ids.get(dev.hardrush.core.lobby.LobbyLayout.Facility.MERCHANT), net.minecraft.world.InteractionHand.MAIN_HAND);
            test.assertTrue(dev.hardrush.lobby.MonsterShop.isShop(monster.player), "Right-clicking the witch model opens the native trading screen");
            var menu = monster.player.containerMenu;
            // The isolated camp body may still select an offer; vanilla moves the projected coins into the payment slot.
            monster.player.connection.handleSelectTrade(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(0));
            test.assertTrue(dev.hardrush.lobby.CampKit.isCoin(menu.getSlot(0).getItem()) && menu.getSlot(0).getItem().getCount() == 6,
                    "Selecting the adult egg offer pays with the projected coins");
            test.assertFalse(menu.getSlot(2).getItem().isEmpty(), "The native result slot shows the paid offer");
            monster.player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                    (short) 2, (byte) 0, ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            test.assertTrue(menu.getCarried().isEmpty(), "Taking the result commits a purchase instead of handing out an item");
        }
        void assertWitchPurchase() {
            var wallet = monster.participant().wallet();
            test.assertValueEqual(wallet.coins(), 0, "The egg cost exactly its first ladder price");
            test.assertTrue(monster.player.containerMenu.getSlot(0).getItem().isEmpty(), "Paid coins leave the payment slot");
            monster.player.closeContainer();
            dev.hardrush.lobby.CampKit.tick(server);
            test.assertValueEqual(coins(), 0, "Closing the shop never returns spent coins");
            boolean projected = false;
            for (int i = 0; i < monster.player.getInventory().getContainerSize(); i++)
                projected |= dev.hardrush.lobby.CampKit.is(monster.player.getInventory().getItem(i))
                        && monster.player.getInventory().getItem(i).is(Items.ZOMBIE_SPAWN_EGG);
            test.assertTrue(projected, "The paid order is shown in the camp backpack");
        }
        int coins() {
            int count = 0;
            for (int i = 0; i < monster.player.getInventory().getContainerSize(); i++)
                if (dev.hardrush.lobby.CampKit.isCoin(monster.player.getInventory().getItem(i))) count += monster.player.getInventory().getItem(i).getCount();
            return count;
        }
        void equipWorldSword() {
            worldGearDrops = new AABB(monster.player.blockPosition()).inflate(6);
            var sword = new ItemEntity(level, monster.player.getX(), monster.player.getY(), monster.player.getZ(), new ItemStack(Items.DIAMOND_SWORD));
            sword.setNoPickUpDelay(); level.addFreshEntity(sword); sword.playerTouch(monster.player);
            test.assertTrue(sword.isRemoved(), "A formal zombie automatically equips the world weapon through the native pickup hook");
            for (int i=0;i<36;i++) if(monster.player.getInventory().getItem(i).is(Items.DIAMOND_SWORD)) monster.player.getInventory().setSelectedSlot(i);
            MonsterRoles.repairLoadout(monster.player);
            test.assertTrue(MonsterRoles.actualEquipment(monster.player, EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), "Selecting the picked-up world weapon equips the native model");
            test.assertValueEqual(worldSwords(), 0, "Exactly one sword transfers from the world into the monster body");
        }
        void spawnEgg(int slot, int dx) {
            monster.player.getInventory().setSelectedSlot(slot);
            var floor = monster.player.blockPosition().offset(dx,-1,0);
            level.setBlockAndUpdate(floor,Blocks.SMOOTH_STONE.defaultBlockState());
            for(int y=1;y<=3;y++) level.setBlockAndUpdate(floor.above(y),Blocks.AIR.defaultBlockState());
            var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false);
            int before = monster.player.getInventory().getItem(slot).getCount();
            test.assertTrue(dev.hardrush.monster.MonsterCompanions.useOn(monster.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit), "Bound egg spawns at a safe block");
            test.assertValueEqual(monster.player.getInventory().getItem(slot).getCount(), before - 1, "Successful egg use consumes exactly one charge");
        }
        void restoreSummonRing() { }
        int worldSwords() {
            return level.getEntitiesOfClass(ItemEntity.class, worldGearDrops).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND_SWORD)).mapToInt(ItemStack::getCount).sum();
        }
        int summons() {
            return level.getEntitiesOfClass(Zombie.class, new AABB(BlockPos.containing(anchor)).inflate(150)).stream()
                    .filter(child -> child.isAlive() && monster.profile.id().equals(MonsterRoles.summonOwner(child))).mapToInt(child -> 1).sum();
        }
        void assertSkillCooldowns() {
            var cooldowns = monster.participant().monsterSkillReadyAtMillis();
            test.assertFalse(cooldowns.containsKey(MonsterSkill.ZOMBIE_SUMMON), "Retired skill never creates a new deadline");
            if (burstReadyAt != 0) test.assertValueEqual(cooldowns.get(MonsterSkill.SKELETON_BURST), burstReadyAt, "Burst deadline survives role changes, defeat, lobby and reconnect");
        }
        void assertLobby(boolean replaced) {
            monster.player = monster.current();
            test.assertTrue(monster.player != null && monster.player.isAlive() && monster.player.level() == LobbyWorld.open(server).holdingLevel()
                    && PlayerProfiles.isLocked(monster.player), "The authoritative native body is isolated in the real lobby");
            test.assertTrue(PlayerProfiles.canMoveInLobby(monster.player), "Verified monster standby may move without competition admission");
            test.assertValueEqual(monster.player.gameMode.getGameModeForPlayer(), net.minecraft.world.level.GameType.ADVENTURE,
                    "Every returned monster body uses adventure mode");
            if (replaced) test.assertTrue(monster.player != dead, "Defeat must create a native replacement rather than heal the dead object");
            test.assertTrue(monster.player.connection.player == monster.player, "The native connection rebinds to the live lobby body");
            test.assertTrue(onlyCampProjections(monster.player), "No role controls or human death inventory enter the lobby");
            test.assertFalse(MonsterRoles.isMonster(monster.player), "Lobby has no active prototype role body");
            test.assertTrue(PlayerLocator.hideFrom(monster.player, target.player),
                    "Committed camp identity hides a monster even when the native body looks human");
            test.assertFalse(PlayerLocator.hideFrom(target.player, monster.player), "Camp monster retains permission to receive human locations");
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
            var field = LobbyRuntime.class.getDeclaredField("BINDINGS"); field.setAccessible(true);
            return ((Map<?, ?>) field.get(null)).get(server);
        }
        int diamonds() {
            // Native loot can drift beyond the original five-block death-query box.
            // Count the whole private arena; UUID visibility separately proves the original drops survive.
            int count = level.getEntitiesOfClass(ItemEntity.class, humanDrops.inflate(128)).stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
            if (count != 3 && !humanDropEntities.isEmpty()) {
                System.out.println("Lobby loot conservation diagnostics: " + lootDiagnostic());
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
                System.out.println("Lobby waiting for original loot section visibility: " + lootDiagnostic());
            }
            if (server.getTickCount() - lootUnavailableSince >= 200) {
                throw new IllegalStateException("Original death loot UUIDs must become visible in an entity-ticking chunk within 200 ticks: "
                        + lootDiagnostic() + ", runtime=" + RoundRuntime.status(server));
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
            try (var store = new SqliteRoundStore(server.getWorldPath(LevelResource.ROOT).resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE))) {
                var saved = RoundSnapshotCodec.decode(store.load(round().roundId()).orElseThrow().payload()).participants().get(monster.profile.id());
                test.assertValueEqual(saved.role(), ParticipantRole.MONSTER_LOBBY, "Real SQLite stores the returned lobby identity");
                test.assertValueEqual(saved.monsterRole(), MonsterRole.CREEPER, "Real SQLite retains role choice across reconnect");
                test.assertValueEqual(saved.monsterDimension(), MonsterDimension.OVERWORLD, "Real SQLite retains the historical compatibility field across reconnect");
                test.assertValueEqual(saved.monsterDeploymentSequence(), 3, "Real SQLite retains sortie label across reconnect");
                test.assertValueEqual(saved.lifeSequence(), 1, "Real SQLite never revives the human through a monster respawn");
                test.assertFalse(saved.monsterSkillReadyAtMillis().containsKey(MonsterSkill.ZOMBIE_SUMMON), "Real SQLite never receives a new retired skill deadline");
                test.assertValueEqual(saved.monsterSkillReadyAtMillis().get(MonsterSkill.SKELETON_BURST), burstReadyAt, "Real SQLite retains the original burst deadline");
                var record = store.participantRecord(round().roundId(), monster.profile.id()).orElseThrow();
                test.assertValueEqual(record.revivalCount(), 0, "Neither defeat nor reconnect consumes/grants a human revival");
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        <T> T service(String name, Class<T> type) {
            try {
                var field = RoundRuntime.class.getDeclaredField(name); field.setAccessible(true);
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
                            test.assertTrue(player.level() == LobbyWorld.open(server).holdingLevel() && PlayerProfiles.isLocked(player), "Native login starts in real profile quarantine");
                        }).thenWaitUntil(() -> await(profiles.isReady(profile.id(), round()), "Real profile journal ready: " + profile.name()));
            }
            void command(String command) {
                if (SpatialInputFixtures.handle(player, command)) return;
                try { server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack()); }
                catch (Exception error) { throw new IllegalStateException(command, error); }
            }
            void openAndClick(Item icon) {
                command("hardrush menu");
                test.assertTrue(dev.hardrush.lobby.LobbyMenus.isMenu(player), "The actual isolated native connection opens the lobby GUI");
                var menu = player.containerMenu;
                for (short slot = 0; slot < 45; slot++) if (menu.getSlot(slot).getItem().is(icon)) {
                    player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(),
                            slot, (byte) 0, ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
                    test.assertTrue(onlyCampProjections(player) && menu.getCarried().isEmpty(), "The real container packet triggers an action without moving a GUI icon");
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
                connection.disconnect(Component.literal("Lobby native reconnect")); connection.handleDisconnection();
                test.assertTrue(current() == null && !profiles.manages(player), "Native disconnect releases both PlayerList and the profile binding");
                channel.finishAndReleaseAll(); player = null;
            }
        }
    }

    static boolean onlyCampProjections(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            // Revival totems, coins and paid orders are camp projections of the committed wallet, never possessions.
            if (!stack.isEmpty() && !RevivalTotems.is(stack) && !dev.hardrush.lobby.CampKit.is(stack)) return false;
        }
        return true;
    }
    static int heldTotems(ServerPlayer player) {
        var inventory = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (RevivalTotems.is(inventory.getItem(slot))) count += inventory.getItem(slot).getCount();
        }
        return count;
    }
    static ItemEntity thrownTotem(ServerPlayer near) {
        return near.level().getEntitiesOfClass(ItemEntity.class, near.getBoundingBox().inflate(8)).stream()
                .filter(item -> RevivalTotems.is(item.getItem())).findFirst().orElse(null);
    }
    /** The real Q-key packet: vanilla removes the stack, RevivalTotems commits it before spawning. */
    static void throwTotem(ServerPlayer player) {
        for (int slot = 0; slot < 9; slot++) if (RevivalTotems.is(player.getInventory().getItem(slot))) {
            player.getInventory().setSelectedSlot(slot);
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.DROP_ITEM, BlockPos.ZERO, Direction.DOWN));
            return;
        }
        throw new IllegalStateException("No revival totem on the hotbar");
    }

    static final class RecordingConnection extends Connection {
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
