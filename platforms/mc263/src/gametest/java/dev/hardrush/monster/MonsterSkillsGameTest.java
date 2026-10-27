package dev.hardrush.monster;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.mixin.MonsterLivingAccessor;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.combat.MonsterCombatRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Native species effects and persistence, using native players with simulated transport only. */
public final class MonsterSkillsGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    private record Shot(AbstractArrow arrow, long nanos, Vec3 direction) {}
    private static final Map<UUID, List<Shot>> SHOTS = new HashMap<>();
    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractArrow arrow && arrow.getOwner() != null) {
                var shots = SHOTS.get(arrow.getOwner().getUUID());
                if (shots != null) shots.add(new Shot(arrow, System.nanoTime(), arrow.getDeltaMovement().normalize()));
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void eggsSaveLoadKeepNativeStateAndOldSortiesDieWithoutLoot(GameTestHelper test) {
        var f = new Fixture(test, 2);
        test.runAfterDelay(80, () -> {
            var children = new ArrayList<Zombie>();
            var unloaded = new ArrayList<Zombie>();
            try (var ignored = f.installCombat()) {
                MonsterRoles.select(f.owner, "zombie");
                for (boolean baby : List.of(true, false)) {
                    f.owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterCompanions.egg(f.owner, baby, false));
                    var floor = f.owner.blockPosition().offset(baby ? 2 : -2, -1, 0);
                    test.assertTrue(MonsterCompanions.useOn(f.owner, InteractionHand.MAIN_HAND,
                            new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0),
                                    net.minecraft.core.Direction.UP, floor, false)), "One egg spawns one native companion");
                }
                children.addAll(f.level.getEntitiesOfClass(Zombie.class, f.owner.getBoundingBox().inflate(8)).stream()
                        .filter(child -> f.owner.getUUID().equals(MonsterCompanions.owner(child))).toList());
                test.assertValueEqual(children.size(), 2, "Two eggs create exactly two companions");
                test.assertValueEqual(children.stream().filter(Zombie::isBaby).count(), 1L, "One child is young and the other is adult");
                test.assertTrue(!MonsterCompanions.event(children.getFirst()).equals(MonsterCompanions.event(children.getLast())),
                        "Each consumed egg gets its own creation event");
                for (var child : children) {
                    test.assertTrue(!child.isPersistenceRequired() && !child.requiresCustomPersistence(),
                            "Ownership does not disable natural despawn");
                    var frozen = MonsterCombatRuntime.source(child).orElseThrow();
                    var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.level.registryAccess());
                    child.saveWithoutId(saved);
                    var restored = EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.LOAD);
                    restored.load(TagValueInput.create(ProblemReporter.DISCARDING, f.level.registryAccess(), saved.buildResult()));
                    MonsterCompanions.configure(restored);
                    test.assertValueEqual(MonsterCompanions.event(restored), MonsterCompanions.event(child), "Native save/load retains the creation event");
                    test.assertValueEqual(MonsterCompanions.owner(restored), f.owner.getUUID(), "Native save/load retains the owner");
                    test.assertValueEqual(MonsterCombatRuntime.source(restored).orElseThrow(), frozen,
                            "Native save/load retains round, owner life and sortie");
                    test.assertValueEqual(restored.isBaby(), child.isBaby(), "Native save/load retains exact age");
                    test.assertValueEqual(restored.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE),
                            child.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE), "Reload preserves native reinforcement chance");
                    for (var slot : EquipmentSlot.values()) test.assertTrue(ItemStack.matches(restored.getItemBySlot(slot), child.getItemBySlot(slot)),
                            "Reload preserves randomized native equipment");
                    restored.setTarget(f.owner);
                    test.assertTrue(restored.getTarget() == null, "The owner is never a legal target");
                    unloaded.add(restored);
                    var natural = EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.LOAD);
                    natural.load(TagValueInput.create(ProblemReporter.DISCARDING, f.level.registryAccess(), saved.buildResult()));
                    natural.setPosRaw(f.owner.getX() + 10000, f.owner.getY(), f.owner.getZ());
                    natural.checkDespawn();
                    test.assertTrue(natural.isRemoved(), "Native far-distance despawn still removes an owned companion");
                }
                var lootArea = f.owner.getBoundingBox().inflate(12);
                var ordinary = children.getFirst();
                ordinary.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                ordinary.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                ordinary.hurtServer(f.level, f.level.damageSources().playerAttack(f.target), 1000);
                test.assertTrue(f.level.getEntitiesOfClass(ItemEntity.class, lootArea).stream()
                        .anyMatch(item -> item.getItem().is(Items.DIAMOND_SWORD)), "Ordinary combat death restores native equipment drops");
                int items = f.level.getEntitiesOfClass(ItemEntity.class, lootArea).size();
                int xp = f.level.getEntitiesOfClass(ExperienceOrb.class, lootArea).size();
                var dismissed = children.getLast();
                dismissed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
                dismissed.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                f.participants.put(f.owner.getUUID(), f.participants.get(f.owner.getUUID())
                        .withRole(ParticipantRole.HUMAN_ACTIVE).withSession("revived-offline", false));
                MonsterRoles.clear(f.owner);
                test.assertTrue(dismissed.isDeadOrDying() && MonsterCompanions.isDismissing(dismissed), "Leaving the sortie kills loaded companions");
                test.assertValueEqual(f.level.getEntitiesOfClass(ItemEntity.class, lootArea).size(), items, "Dismissal creates no new loot");
                test.assertValueEqual(f.level.getEntitiesOfClass(ExperienceOrb.class, lootArea).size(), xp, "Dismissal creates no new XP");
                children.forEach(Entity::discard);
                for (var restored : unloaded) {
                    f.level.addFreshEntity(restored);
                    test.assertTrue(restored.isDeadOrDying() && MonsterCompanions.isDismissing(restored), "An old saved outing is rejected on entity load");
                    restored.setTarget(f.target);
                    test.assertTrue(restored.getTarget() == null, "An invalid old sortie cannot regain a target");
                }
            } finally {
                children.forEach(Entity::discard); unloaded.forEach(Entity::discard);
                f.level.getEntitiesOfClass(ItemEntity.class, f.owner.getBoundingBox().inflate(12)).forEach(Entity::discard);
                f.close();
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void nativeBabyNavigationPursuesLegalHumanWithoutAttackingItsOwner(GameTestHelper test) {
        var f = new Fixture(test, 2);
        test.runAfterDelay(80, () -> {
            var children = new ArrayList<Zombie>();
            var origins = new HashMap<UUID, Vec3>();
            // Keep native entity ticks within this temporary round so unrelated test commits
            // cannot clear the owner. Native melee retry cooldowns also need advancing world time.
            try (var ignored = f.installCombat()) {
                for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++)
                    f.level.setBlockAndUpdate(BlockPos.containing(f.center).offset(dx, 3, dz), Blocks.STONE.defaultBlockState());
                f.target.teleportTo(f.center.x, f.center.y, f.center.z + 60);
                f.target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); f.target.setHealth(1000);
                MonsterRoles.select(f.owner, "zombie");
                for (boolean baby : List.of(true, false)) {
                    f.owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterCompanions.egg(f.owner, baby, false));
                    var floor = f.owner.blockPosition().offset(baby ? 2 : -2, -1, 0);
                    test.assertTrue(MonsterCompanions.useOn(f.owner, InteractionHand.MAIN_HAND,
                            new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0),
                                    net.minecraft.core.Direction.UP, floor, false)), "The adult and baby spawn under a roof");
                }
                children.addAll(f.level.getEntitiesOfClass(Zombie.class, f.owner.getBoundingBox().inflate(8)).stream()
                        .filter(child -> f.owner.getUUID().equals(MonsterCompanions.owner(child))).toList());
                test.assertValueEqual(children.size(), 2, "The AI sample has two real native companions");
                for (var child : children) {
                    test.assertFalse(child.isNoAi(), "The sample retains native zombie AI");
                    origins.put(child.getUUID(), child.position());
                }
                boolean pursued = false;
                var clock = (net.minecraft.world.level.storage.ServerLevelData) f.level.getLevelData();
                long savedTime = clock.getGameTime();
                try {
                    for (int tick = 0; tick < 200 && !pursued; tick++) {
                        clock.setGameTime(savedTime + tick + 1);
                        test.assertValueEqual(f.level.getGameTime(), savedTime + tick + 1,
                                "The isolated AI step advances the native 20-tick melee retry clock");
                        // First let native melee record a no-target check, then introduce the human.
                        // This deterministically exercises its retry clock instead of depending on random goal order.
                        if (tick == 5) f.target.teleportTo(f.center.x, f.center.y, f.center.z + 6);
                        for (var child : children) f.level.tickNonPassenger(child);
                        pursued = children.stream().anyMatch(child -> child.getTarget() == f.target
                                && child.getNavigation().isInProgress()
                                && ((dev.hardrush.monster.mixin.MonsterMobAccessor) child).hardrush$goalSelector().getAvailableGoals().stream()
                                    .anyMatch(goal -> goal.isRunning() && goal.getGoal() instanceof net.minecraft.world.entity.ai.goal.MeleeAttackGoal)
                                && child.distanceToSqr(f.target) < origins.get(child.getUUID()).distanceToSqr(f.target.position())
                                && child.position().subtract(origins.get(child.getUUID())).horizontalDistanceSqr() > 1);
                    }
                } finally { clock.setGameTime(savedTime); }
                test.assertTrue(pursued, "Native target selection and navigation pursue the legal human");
                for (var child : children) test.assertTrue(child.getTarget() != f.owner,
                        "Native AI never selects its owner even when the owner is closer");
                test.assertValueEqual(f.owner.getHealth(), f.owner.getMaxHealth(), "The owner receives no native companion melee damage");
            } finally { children.forEach(Entity::discard); f.close(); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 1800)
    public void burstUsesCurrentAimEverySixHundredMillisAndClearCancelsOnlyUnfiredArrows(GameTestHelper test) {
        var f = new Fixture(test, 2);
        var shots = new ArrayList<Shot>();
        var cancelled = new ArrayList<Shot>();
        SHOTS.put(f.owner.getUUID(), shots); SHOTS.put(f.target.getUUID(), cancelled);
        MonsterRoles.select(f.owner, "skeleton"); MonsterRoles.select(f.target, "skeleton");
        f.owner.setYRot(0); f.owner.setXRot(0);
        test.assertTrue(MonsterRoles.burst(f.owner), "The first shot is emitted immediately");
        test.assertValueEqual(shots.size(), 1, "A burst emits exactly one immediate arrow");
        f.owner.getInventory().setSelectedSlot(0);
        test.assertFalse(MonsterRoles.useControl(f.owner, InteractionHand.MAIN_HAND), "Ordinary draw cannot overlap the burst");
        MonsterRoles.releaseBow(f.owner);
        test.assertValueEqual(shots.size(), 1, "An ordinary release cannot add a burst arrow");
        f.owner.setYRot(90);
        test.assertTrue(MonsterRoles.burst(f.target), "A second body's burst begins");
        var alreadyFired = cancelled.getFirst().arrow();
        MonsterRoles.clear(f.target);
        test.assertFalse(alreadyFired.isRemoved(), "Clearing a body preserves the already-fired arrow");
        test.onEachTick(() -> { if (shots.size() == 2) f.owner.setYRot(180); });
        test.startSequence().thenWaitUntil(() -> {
                    // GameTest may advance thousands of ticks per real second. Give
                    // the production monotonic 1.2-second scheduler real time to run;
                    // the assertions below still require both exact native intervals.
                    if (shots.size() < 3) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    test.assertValueEqual(shots.size(), 3, "The complete burst emits three arrows");
                })
                .thenExecute(() -> {
                    try {
                        test.assertValueEqual(cancelled.size(), 1, "Leaving the body cancels both still-unfired arrows");
                        for (int i = 1; i < 3; i++) test.assertTrue(shots.get(i).nanos() - shots.get(i - 1).nanos() >= 600_000_000L,
                                "Consecutive arrows respect the real 0.6-second interval");
                        test.assertTrue(shots.get(0).direction().z > .8 && shots.get(1).direction().x < -.8
                                        && shots.get(2).direction().z < -.8,
                                "Each shot uses the owner's current aim, with native scatter and no homing");
                        for (var shot : shots) {
                            test.assertValueEqual(shot.arrow().pickup, AbstractArrow.Pickup.DISALLOWED, "Role arrows cannot be farmed");
                            test.assertFalse(shot.arrow().isCritArrow(), "Native mob arrows do not acquire player full-draw critical damage");
                        }
                    } finally {
                        SHOTS.remove(f.owner.getUUID()); SHOTS.remove(f.target.getUUID());
                        shots.forEach(shot -> shot.arrow().discard()); cancelled.forEach(shot -> shot.arrow().discard()); f.close();
                    }
                }).thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void creeperCanWalkDuringThirtyTickFuseAndDefeatCancelsPendingBlast(GameTestHelper test) {
        var f = new Fixture(test, 3);
        test.runAfterDelay(80, () -> {
            MonsterRoles.select(f.owner, "creeper"); MonsterRoles.select(f.target, "creeper");
            var victim = f.players.get(2);
            victim.teleportTo(f.target.getX() + 2, f.target.getY(), f.target.getZ());
            double walkingSpeed = f.owner.getAttributeValue(Attributes.MOVEMENT_SPEED);
            Vec3 origin = f.owner.position();
            test.assertTrue(MonsterRoles.ignite(f.owner) && MonsterRoles.ignite(f.target), "Both native fuses begin");
            test.assertTrue(walkingSpeed > 0, "The deployed creeper has a positive walking speed");
            test.assertValueEqual(f.owner.getAttributeValue(Attributes.MOVEMENT_SPEED), walkingSpeed,
                    "Priming preserves the creeper's normal walking speed");
            f.owner.setDeltaMovement(Vec3.ZERO);
            f.owner.setSpeed((float) f.owner.getAttributeValue(Attributes.MOVEMENT_SPEED));
            f.owner.moveRelative(f.owner.getSpeed(), new Vec3(0, 0, 1));
            f.owner.move(net.minecraft.world.entity.MoverType.SELF, f.owner.getDeltaMovement());
            f.owner.setDeltaMovement(Vec3.ZERO);
            test.assertTrue(f.owner.position().subtract(origin).horizontalDistanceSqr() > 0,
                    "Native walking acceleration moves the body during its fuse");
            test.assertTrue(MonsterRoles.select(f.owner, "zombie").contains("膨胀期间"), "Priming cannot switch species");
            f.target.hurtServer(f.level, f.level.damageSources().genericKill(), Float.MAX_VALUE);
            test.runAfterDelay(29, () -> {
                test.assertTrue(f.owner.isAlive() && MonsterRoles.isIgniting(f.owner),
                        "Walking does not cancel or shorten the 30-tick fuse");
                test.assertValueEqual(f.owner.getAttributeValue(Attributes.MOVEMENT_SPEED), walkingSpeed,
                        "Normal walking speed remains available throughout the fuse");
            });
            test.runAfterDelay(31, () -> {
                try {
                    test.assertFalse(f.owner.isAlive(), "The native 30-tick warning ends the igniting body");
                    test.assertValueEqual(victim.getHealth(), 20f, "An early-defeated body's pending blast never damages a nearby human");
                    test.assertTrue(f.level.getBlockState(f.owner.blockPosition().below()).is(Blocks.SMOOTH_STONE), "The live blast preserves blocks");
                    test.assertTrue(f.level.getBlockState(f.owner.blockPosition()).isAir(), "The live blast does not create fire");
                } finally { f.close(); }
                test.succeed();
            });
        });
    }

    @GameTest(maxTicks = 400)
    public void creeperDamageMatchesActualNativeCreeperAcrossDifficultyDistanceCoverAndArmor(GameTestHelper test) {
        var f = new Fixture(test, 2);
        test.runAfterDelay(80, () -> {
            var difficulty = f.level.getDifficulty();
            boolean griefing = f.level.getGameRules().get(GameRules.MOB_GRIEFING);
            try {
                f.level.getGameRules().set(GameRules.MOB_GRIEFING, false, f.level.getServer());
                f.owner.teleportTo(f.center.add(-20, 0, 0).x, f.center.y, f.center.z);
                f.target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                int comparisons = 0;
                for (var mode : List.of(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)) {
                    f.level.getServer().setDifficulty(mode, true);
                    for (double distance : List.of(2d, 4d, 7d)) for (boolean cover : List.of(false, true))
                            for (boolean armored : List.of(false, true)) {
                        f.wall(cover);
                        f.prepareVictim(distance, armored);
                        var source = EntityTypes.CREEPER.create(f.level, EntitySpawnReason.COMMAND);
                        source.snapTo(f.center);
                        MonsterRoles.nativeCreeperBlast(source, f.owner.getUUID(), f.center, 3);
                        float actual = 1000 - f.target.getHealth();
                        var actualSource = f.target.getLastDamageSource();
                        test.assertTrue(f.level.getBlockState(BlockPos.containing(f.center).below()).is(Blocks.SMOOTH_STONE),
                                "The formal native blast leaves solid blocks intact");
                        test.assertTrue(f.level.getBlockState(BlockPos.containing(f.center)).isAir(), "The formal native blast creates no fire");
                        source.discard();
                        f.prepareVictim(distance, armored);
                        var nativeCreeper = EntityTypes.CREEPER.create(f.level, EntitySpawnReason.COMMAND);
                        nativeCreeper.setNoAi(true); nativeCreeper.setNoGravity(true); nativeCreeper.snapTo(f.center); nativeCreeper.ignite();
                        // Tick the real private vanilla explodeCreeper path synchronously. Global
                        // difficulty and mobGriefing are restored before this callback yields.
                        for (int tick = 0; tick < 31 && !nativeCreeper.isRemoved(); tick++) nativeCreeper.tick();
                        float expected = 1000 - f.target.getHealth();
                        test.assertValueEqual(actual, expected, "Actual native Creeper damage: " + mode + "/distance=" + distance
                                + "/cover=" + cover + "/armor=" + armored);
                        if (actual > 0) test.assertTrue(actualSource != null && actualSource.getEntity() instanceof Creeper
                                        && actualSource.typeHolder().equals(f.target.getLastDamageSource().typeHolder()),
                                "Damage uses the same native Creeper source type and difficulty scaling");
                        nativeCreeper.discard(); comparisons++;
                    }
                }
                test.assertValueEqual(comparisons, 36, "All difficulty/distance/cover/armor combinations were compared");
            } finally {
                f.level.getServer().setDifficulty(difficulty, true);
                f.level.getGameRules().set(GameRules.MOB_GRIEFING, griefing, f.level.getServer());
                f.wall(false); f.close();
            }
            test.succeed();
        });
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper test;
        final ServerLevel level;
        final Vec3 center;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();
        final Map<UUID, Participant> participants = new HashMap<>();
        final List<net.minecraft.world.level.ChunkPos> forced = new ArrayList<>();
        final UUID round = UUID.randomUUID();
        final ServerPlayer owner, target;
        Fixture(GameTestHelper test, int count) {
            this.test = test; level = test.getLevel(); center = new Vec3(20000 + ARENAS.getAndIncrement() * 128 + .5, 150, .5);
            for (int dx = -2; dx <= 4; dx++) for (int dz = -2; dz <= 2; dz++) {
                var chunk = new net.minecraft.world.level.ChunkPos((int)center.x >> 4, (int)center.z >> 4);
                chunk = new net.minecraft.world.level.ChunkPos(chunk.x() + dx, chunk.z() + dz);
                level.setChunkForced(chunk.x(), chunk.z(), true); forced.add(chunk);
            }
            for (int dx = -8; dx <= count * 24; dx++) for (int dz = -8; dz <= 8; dz++) {
                var pos = BlockPos.containing(center).offset(dx, 0, dz);
                level.setBlockAndUpdate(pos.below(), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y < 5; y++) level.setBlockAndUpdate(pos.above(y), Blocks.AIR.defaultBlockState());
            }
            for (int index = 0; index < count; index++) {
                var profile = new GameProfile(UUID.randomUUID(), "HRSkill" + ARENAS.get() + index);
                var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
                var connection = new SinkConnection(); channels.add(new EmbeddedChannel(connection));
                dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> level.getServer().getPlayerList().placeNewPlayer(connection, player,
                        CommonListenerCookie.createInitial(profile, false)));
                player.teleportTo(center.x + index * 24, center.y, center.z);
                player.setGameMode(GameType.SURVIVAL); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                players.add(player);
                participants.put(player.getUUID(), new Participant(player.getUUID(), profile.name(),
                        index == 0 ? ParticipantRole.MONSTER_DEPLOYED : ParticipantRole.HUMAN_ACTIVE,
                        true, false, 0, 0, 1, false, true, MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 1));
            }
            owner = players.get(0); target = players.get(1);
        }
        MonsterCombatRuntime.Scope installCombat() {
            var combat = dev.hardrush.gametest.RuntimeFixtures.monsterCombat(level.getServer(), new MonsterCombatRuntime.Access() {
                public Optional<RoundSnapshot> snapshot() { return Optional.of(new RoundSnapshot(round, 1, 42, 0, RulesConfig.defaults(),
                        RoundPhase.RUNNING, PauseReason.NONE, 0, 0, participants, false, false, null)); }
                public boolean competitionLocked() { return false; }
                public boolean formalLevel(ServerLevel candidate) { return candidate == level; }
            });
            var evidence = dev.hardrush.gametest.RuntimeFixtures.combat(level.getServer(), new CombatRuntime.RoundAccess() {
                public Optional<UUID> roundOf(ServerLevel candidate) { return candidate == level ? Optional.of(round) : Optional.empty(); }
                public Optional<CombatActor> actor(UUID roundId, UUID id) { return Optional.ofNullable(participants.get(id))
                        .map(p -> new CombatActor(p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence())); }
            }, event -> {});
            return () -> { evidence.close(); combat.close(); };
        }
        void wall(boolean closed) {
            for (int dx = -3; dx <= 3; dx++) for (int y = 0; y < 4; y++)
                level.setBlockAndUpdate(BlockPos.containing(center).offset(dx, y, 1),
                        (closed ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        void prepareVictim(double distance, boolean armored) {
            target.stopUsingItem(); target.removeAllEffects(); target.clearFire(); target.setHealth(1000);
            target.setAbsorptionAmount(0); target.setInvulnerableTime(0); target.damageCooldownTime = 0;
            target.teleportTo(center.x, center.y, center.z + distance); target.setDeltaMovement(Vec3.ZERO);
            for (var slot : EquipmentSlot.values()) target.setItemSlot(slot, ItemStack.EMPTY);
            if (armored) for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
                target.setItemSlot(slot, new ItemStack(switch (slot) {
                    case HEAD -> Items.DIAMOND_HELMET; case CHEST -> Items.DIAMOND_CHESTPLATE;
                    case LEGS -> Items.DIAMOND_LEGGINGS; default -> Items.DIAMOND_BOOTS;
                }));
            ((MonsterLivingAccessor)target).hardrush$detectEquipmentUpdates();
        }
        @Override public void close() {
            for (var player : players) { MonsterRoles.clear(player); level.getServer().getPlayerList().remove(player); }
            for (var channel : channels) channel.finishAndReleaseAll();
            for (var chunk : forced) level.setChunkForced(chunk.x(), chunk.z(), false);
        }
    }
    private static final class SinkConnection extends Connection {
        SinkConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    }
}
