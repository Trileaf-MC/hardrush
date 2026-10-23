package dev.hardrush.gametest;

import dev.hardrush.p0.monster.MonsterAiIdentity;
import dev.hardrush.p0.monster.MonsterRoles;
import dev.hardrush.p0.monster.mixin.MonsterMobAccessor;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.PiglinSpecificSensor;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real server AI and native bodies; no graphical client or computer use. */
public final class MonsterAiIdentityGameTest {
    @GameTest(maxTicks=300)
    public void nativeTargetGoalsAgreeForBodiesAndPlayers(GameTestHelper test) {
        var f = new Fixture(test);
        test.runAfterDelay(80, () -> {
            try (f) {
                var forms = List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER);
                var sources = List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER,
                        EntityTypes.IRON_GOLEM, EntityTypes.WOLF, EntityTypes.WITHER);
                for (var form : forms) {
                    String name = form == EntityTypes.ZOMBIE ? "zombie" : form == EntityTypes.SKELETON ? "skeleton" : "creeper";
                    MonsterRoles.select(f.player, name);
                    for (var sourceType : sources) {
                        var source = f.mob(sourceType, new Vec3(0, 0, 3));
                        f.player.setGameMode(GameType.SPECTATOR);
                        var control = f.mob(form, Vec3.ZERO);
                        boolean nativeSelected = nearest(source) == control;
                        control.discard(); f.entities.remove(control);
                        f.player.setGameMode(GameType.ADVENTURE);
                        var selected = nearest(source);
                        test.assertValueEqual(selected == f.player, nativeSelected,
                                "Native goal equivalence: " + sourceType + " -> " + name);
                        test.assertTrue(selected == null || selected == f.player, "AI stores the actual player, never a template");
                        if (source instanceof IronGolem golem) {
                            test.assertValueEqual(selected == f.player, !name.equals("creeper"), "Golems exclude creepers");
                            golem.setPlayerCreated(true);
                            test.assertValueEqual(nearest(golem) == f.player, !name.equals("creeper"), "Player-built golems see native species");
                        }
                        if (source instanceof Wolf wolf) {
                            test.assertValueEqual(selected == f.player, name.equals("skeleton"), "Wolf hunts skeletons only");
                            wolf.tame(f.player);
                            test.assertTrue(nearest(wolf) == null, "A wolf never hunts its own owner");
                        }
                        if (sourceType == EntityTypes.WITHER)
                            test.assertValueEqual(selected == f.player, name.equals("creeper"), "Wither ignores undead species but targets creepers");
                        source.discard(); f.entities.remove(source);
                    }
                }
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks=300)
    public void nativeOcelotAvoidanceAndAppearanceOnlyIdentity(GameTestHelper test) {
        var f = new Fixture(test);
        test.runAfterDelay(80, () -> {
            try (f) {
                var ocelot = f.mob(EntityTypes.OCELOT, new Vec3(0,0,3));
                test.assertTrue(avoidsHuman(ocelot), "A wild ocelot has a native escape path from a human");
                MonsterRoles.select(f.player, "creeper");
                test.assertFalse(avoidsHuman(ocelot), "A wild ocelot does not flee a creeper as if it were a human");
                MonsterRoles.clear(f.player);
                dev.hardrush.p0.disguise.DisguiseProbe.apply(f.player, "zombie");
                var zombie = f.mob(EntityTypes.ZOMBIE, new Vec3(0,0,-3));
                test.assertTrue(MonsterAiIdentity.species(f.player) == f.player && nearest(zombie) == f.player,
                        "Appearance-only disguises never alter native AI identity");
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks=400)
    public void wolfNaturallyNavigatesToAndDamagesSkeletonPlayer(GameTestHelper test) {
        var f = new Fixture(test);
        MonsterRoles.select(f.player, "skeleton");
        f.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        f.player.setHealth(100);
        var wolf = f.mob(EntityTypes.WOLF, new Vec3(0, 0, 5));
        var origin = wolf.position();
        test.runAfterDelay(80, () -> {
            wolf.setNoAi(false);
            test.startSequence().thenWaitUntil(() -> {
                test.assertTrue(wolf.getTarget() == f.player && wolf.position().distanceToSqr(origin) > 1
                        && f.player.getHealth() < 100, "Native wolf selection, navigation and melee reach the real skeleton body");
            }).thenExecute(f::close).thenSucceed();
        });
    }

    @GameTest(maxTicks=300)
    public void provocationRetaliatesAgainstRealPlayerAndKeepsSpeciesExclusions(GameTestHelper test) {
        var f = new Fixture(test);
        test.runAfterDelay(80, () -> {
            try (f) {
                MonsterRoles.select(f.player, "zombie");
                var zombie = f.mob(EntityTypes.ZOMBIE, new Vec3(0, 0, 1));
                zombie.tickCount = 10;
                test.assertTrue(MonsterRoles.attack(f.player, zombie), "Player zombie can attack a natural zombie");
                test.assertTrue(zombie.getLastHurtByMob() == f.player, "Native hurt history resolves the player rather than its model");
                var revenge = new HurtByTargetGoal(zombie);
                test.assertTrue(revenge.canUse(), "Provoked zombie may retaliate against a zombie player");
                revenge.start();
                test.assertTrue(zombie.getTarget() == f.player, "Retaliation stores a real target");
                float health = f.player.getHealth();
                test.assertTrue(zombie.doHurtTarget(f.player.level(), f.player) && f.player.getHealth() < health,
                        "Native retaliation damages the actual player");
                revenge.stop(); zombie.discard(); f.entities.remove(zombie);
                MonsterRoles.select(f.player, "skeleton");
                var skeleton = f.mob(EntityTypes.SKELETON, new Vec3(0, 0, 1));
                skeleton.tickCount = 10;
                skeleton.setLastHurtByMob(MonsterRoles.nativeModel(f.player));
                test.assertTrue(new HurtByTargetGoal(skeleton).canUse(), "Attacker history is fresh before checking the excluded species");
                test.assertFalse(new HurtByTargetGoal(skeleton, net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class).canUse(),
                        "Native ignored attacker classes apply to player species too");
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks=300)
    public void brainSensorsRecognizeZombieThreatAndExcludePlayerSpecificMemories(GameTestHelper test) {
        var f = new Fixture(test);
        test.runAfterDelay(80, () -> {
            try (f) {
                MonsterRoles.select(f.player, "zombie");
                var villager = f.mob(EntityTypes.VILLAGER, new Vec3(0, 0, 3));
                var hostiles = new VillagerHostilesSensor();
                villager.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                        new NearestVisibleLivingEntities(f.player.level(), villager, List.of(f.player)));
                for (int i=0; i<40; i++) hostiles.tick(f.player.level(), villager);
                test.assertTrue(villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).orElse(null) == f.player,
                        "Villager senses a zombie threat and remembers its real player");
                MonsterRoles.select(f.player, "skeleton");
                for (int i=0; i<40; i++) hostiles.tick(f.player.level(), villager);
                test.assertTrue(villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).isEmpty(),
                        "Skeleton does not inherit zombie fear");
                var piglin = f.mob(EntityTypes.PIGLIN, new Vec3(1, 0, 3));
                var players = new PlayerSensor();
                for (int i=0; i<40; i++) players.tick(f.player.level(), piglin);
                test.assertTrue(piglin.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER).isEmpty(),
                        "Player sensor does not classify a skeleton as a human");
                piglin.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
                        new NearestVisibleLivingEntities(f.player.level(), piglin, List.of(f.player)));
                var piglins = new PiglinSpecificSensor();
                for (int i=0; i<40; i++) piglins.tick(f.player.level(), piglin);
                test.assertTrue(piglin.getBrain().getMemory(MemoryModuleType.NEAREST_TARGETABLE_PLAYER_NOT_WEARING_GOLD).isEmpty(),
                        "Piglin does not attack a monster player for missing gold armor");
                piglin.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, MonsterRoles.nativeModel(f.player));
                test.assertTrue(piglin.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == f.player,
                        "Brain cannot retain an unregistered body template");
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks=300)
    public void bodyTransitionsClearStaleTargetsAndHumanOnlyQueriesKeepOrdinaryHumans(GameTestHelper test) {
        var f = new Fixture(test);
        var human = MonsterRoleGameTest.actor(test);
        human.teleportTo(f.player.getX()+2, f.player.getY(), f.player.getZ());
        test.runAfterDelay(80, () -> {
            try (f) {
                var zombie = f.mob(EntityTypes.ZOMBIE, new Vec3(0, 0, 3));
                zombie.setTarget(f.player);
                MonsterRoles.select(f.player, "zombie");
                test.assertTrue(zombie.getTarget() == null, "Human-only automatic target is cleared on transformation");
                test.assertTrue(nearest(zombie) == human, "A nearby ordinary human remains a valid native target");
                var enderman = f.mob(EntityTypes.ENDERMAN, new Vec3(0, 0, 4));
                var conditions = TargetingConditions.forCombat().ignoreLineOfSight().selector((target, level) -> {
                    test.assertTrue(target instanceof net.minecraft.world.entity.player.Player, "Player-only predicates never receive monster templates");
                    return true;
                });
                test.assertTrue(f.player.level().getNearestPlayer(conditions, enderman) == human,
                        "Special player-only queries ignore monster bodies");
                var nearby = f.player.level().getNearbyPlayers(conditions, enderman, enderman.getBoundingBox().inflate(10));
                test.assertTrue(nearby.contains(human) && !nearby.contains(f.player), "Phantom/dragon player scans retain only humans");
                var golem = f.mob(EntityTypes.IRON_GOLEM, new Vec3(0, 0, 2));
                golem.setTarget(f.player);
                MonsterRoles.select(f.player, "creeper");
                test.assertTrue(golem.getTarget() == null, "Golem drops an old target that became a creeper");
                var skeleton = f.mob(EntityTypes.SKELETON, new Vec3(0,0,-2));
                skeleton.tickCount = 10;
                skeleton.setLastHurtByMob(f.player);
                skeleton.setTarget(f.player);
                MonsterRoles.select(f.player, "skeleton");
                test.assertTrue(skeleton.getTarget() == f.player, "Vanilla skeleton retaliation survives a same-species body transition");
                var targets = ((MonsterMobAccessor)skeleton).hardrush$targetSelector();
                targets.removeAllGoals(goal -> true);
                targets.addGoal(1, new HurtByTargetGoal(skeleton, net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class));
                MonsterRoles.select(f.player, "zombie");
                MonsterRoles.select(f.player, "skeleton");
                test.assertTrue(skeleton.getTarget() == null, "Changing species rechecks the native retaliation exclusions");
                var phantom = f.mob(EntityTypes.PHANTOM, new Vec3(0,1,3));
                phantom.setLastHurtByMob(f.player);
                phantom.setTarget(f.player);
                MonsterRoles.select(f.player, "zombie");
                test.assertTrue(phantom.getTarget() == null, "Hurt history cannot invent retaliation for an AI without that behavior");
                MonsterRoles.clear(f.player);
                test.assertTrue(MonsterAiIdentity.species(f.player) == f.player, "Restoring human removes the species projection");
                human.setGameMode(GameType.SPECTATOR);
                test.assertTrue(nearest(zombie) == f.player, "Restored human is selected normally");
                MonsterRoles.select(f.player, "skeleton");
                MonsterRoles.onDisconnect(f.player);
                MonsterRoles.onJoin(f.player);
                test.assertTrue(MonsterAiIdentity.matches(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class, f.player),
                        "Reconnect binds the new native species body");
            } finally { MonsterRoleGameTest.remove(human); }
            test.succeed();
        });
    }

    @GameTest(maxTicks=300)
    public void dimensionChangesAndDisconnectsCannotLeaveTargetableTemplates(GameTestHelper test) {
        var f = new Fixture(test);
        test.runAfterDelay(80, () -> {
            var originalLevel = f.player.level();
            try (f) {
                MonsterRoles.select(f.player, "skeleton");
                var oldBody = MonsterRoles.nativeModel(f.player);
                var zombie = f.mob(EntityTypes.ZOMBIE, new Vec3(0,0,3));
                var nether = originalLevel.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
                f.player.teleportTo(nether, .5, 150, .5, java.util.Set.of(), 0, 0, true);
                zombie.setTarget(oldBody);
                test.assertTrue(zombie.getTarget() == null, "A template cannot hide the owner's different dimension");
                test.assertFalse(TargetingConditions.forCombat().test(originalLevel, zombie, oldBody),
                        "Old-world targeting rejects a body whose real player left the world");
                MonsterRoles.tick(originalLevel.getServer());
                test.assertTrue(MonsterRoles.nativeModel(f.player).level() == nether
                        && MonsterAiIdentity.matches(net.minecraft.world.entity.monster.skeleton.AbstractSkeleton.class, f.player),
                        "Native identity rebinds in the new dimension");
                f.player.teleportTo(originalLevel, zombie.getX(), zombie.getY(), zombie.getZ()+2, java.util.Set.of(), 0, 0, true);
                MonsterRoles.tick(originalLevel.getServer());
                var disconnectedBody = MonsterRoles.nativeModel(f.player);
                MonsterRoles.onDisconnect(f.player);
                originalLevel.getServer().getPlayerList().remove(f.player);
                zombie.setTarget(disconnectedBody);
                test.assertTrue(zombie.getTarget() == null && MonsterAiIdentity.real(disconnectedBody) == null,
                        "An offline owner's internal template is never a world target");
            }
            test.succeed();
        });
    }

    private static LivingEntity nearest(Mob mob) {
        for (var wrapped : ((MonsterMobAccessor)mob).hardrush$targetSelector().getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof NearestAttackableTargetGoal<?> goal)) continue;
            for (int i=0; i<100; i++) if (goal.canUse()) {
                goal.start();
                var target = mob.getTarget();
                goal.stop();
                if (target != null) return target;
            }
        }
        return null;
    }

    private static boolean avoidsHuman(Mob mob) {
        for (var wrapped : ((MonsterMobAccessor)mob).hardrush$goalSelector().getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof net.minecraft.world.entity.ai.goal.AvoidEntityGoal<?> goal)) continue;
            for (int i=0; i<100; i++) if (goal.canUse()) { goal.stop(); return true; }
        }
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final List<Entity> entities = new ArrayList<>();
        Fixture(GameTestHelper test) {
            player = MonsterRoleGameTest.actor(test);
            for (int x=-6;x<=6;x++) for (int z=-6;z<=6;z++)
                player.level().setBlockAndUpdate(player.blockPosition().offset(x,3,z), Blocks.BARRIER.defaultBlockState());
        }
        <T extends Mob> T mob(EntityType<T> type, Vec3 offset) {
            var mob = type.create(player.level(), EntitySpawnReason.COMMAND);
            mob.setNoAi(true); mob.snapTo(player.position().add(offset));
            mob.setOnGround(true);
            if (mob.getAttribute(Attributes.FOLLOW_RANGE) != null) mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8);
            player.level().addFreshEntity(mob); entities.add(mob);
            return mob;
        }
        @Override public void close() {
            entities.forEach(Entity::discard);
            MonsterRoleGameTest.remove(player);
        }
    }
}
