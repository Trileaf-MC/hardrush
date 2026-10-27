package dev.hardrush.gametest;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.monster.MonsterCompanions;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.monster.mixin.MonsterMobAccessor;
import dev.hardrush.combat.CombatOriginHolder;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.FrozenMonsterSource;
import dev.hardrush.combat.MonsterSourceHolder;
import dev.hardrush.combat.MonsterCombatRuntime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Proxy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Headless native entity integration; does not use a real client. */
public final class MonsterCompanionGameTest {
    @GameTest(maxTicks = 200)
    public void eggsHaveExactAgeOneMobAndGuaranteedDrownedTrident(GameTestHelper test) {
        var player = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(player, "zombie");
                for (boolean drowned : List.of(false, true)) for (boolean baby : List.of(false, true)) {
                    Zombie zombie = summon(test, player, baby, drowned);
                    test.assertTrue(zombie.isBaby() == baby, "Egg age remains exact after native finalization");
                    test.assertTrue((zombie instanceof Drowned) == drowned, "Egg species remains exact");
                    test.assertTrue(zombie.getVehicle() == null && zombie.getPassengers().isEmpty(), "An egg never creates a chicken jockey");
                    test.assertTrue(player.getMainHandItem().isEmpty(), "Successful egg consumes exactly one item");
                    if (drowned) test.assertTrue(zombie.getMainHandItem().is(Items.TRIDENT), "Every drowned egg receives a trident");
                    zombie.discard();
                }
            } finally { clean(player); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void eggCannotEditSpawnerOrBeReusedAfterNewSortie(GameTestHelper test) {
        var player = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(player, "zombie");
                var egg = MonsterCompanions.egg(player, false, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, egg);
                var spawner = player.blockPosition().east(2);
                player.level().setBlockAndUpdate(spawner, Blocks.SPAWNER.defaultBlockState());
                test.assertTrue(!MonsterCompanions.useOn(player, InteractionHand.MAIN_HAND, hit(spawner)), "Bound egg does not mutate spawners");
                test.assertValueEqual(egg.getCount(), 1, "Rejected spawner use does not consume the egg");
                player.level().setBlockAndUpdate(spawner, Blocks.AIR.defaultBlockState());
                MonsterRoles.clear(player);
                MonsterRoles.select(player, "zombie");
                player.setItemInHand(InteractionHand.MAIN_HAND, egg);
                test.assertTrue(!MonsterCompanions.useOn(player, InteractionHand.MAIN_HAND, hit(spawner.below())), "Old sortie egg cannot be reused");
                test.assertValueEqual(egg.getCount(), 1, "Rejected stale egg is not consumed");
            } finally { clean(player); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void airUseRequiresSourceLiquidAndSpawnsOneDrowned(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            var water = owner.blockPosition().east(2);
            try {
                MonsterRoles.select(owner, "zombie");
                var egg = MonsterCompanions.egg(owner, false, true);
                owner.setItemInHand(InteractionHand.MAIN_HAND, egg);
                owner.setXRot(-90);
                test.assertTrue(!MonsterCompanions.use(owner, InteractionHand.MAIN_HAND), "Ordinary air never spawns a companion");
                test.assertValueEqual(egg.getCount(), 1, "Air rejection does not consume the egg");
                owner.level().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
                // The vanilla POV ray uses head yaw; simulated transport must update both body and head.
                owner.setYRot(-90); owner.setYHeadRot(-90); owner.setXRot(40);
                test.assertTrue(MonsterCompanions.use(owner, InteractionHand.MAIN_HAND), "Native source-liquid raycast accepts a water surface");
                var drowned = owner.level().getEntitiesOfClass(Drowned.class, owner.getBoundingBox().inflate(8)).stream()
                        .filter(mob -> owner.getUUID().equals(MonsterCompanions.owner(mob))).toList();
                test.assertValueEqual(drowned.size(), 1, "Water-surface use creates exactly one drowned");
                test.assertTrue(!drowned.getFirst().isBaby() && drowned.getFirst().getMainHandItem().is(Items.TRIDENT),
                        "Water-surface spawn retains age and guaranteed native trident");
            } finally {
                owner.level().setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
                clean(owner);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void companionsIgnoreOwnerAndRequireAnOrderForOtherMonsters(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var other = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                MonsterRoles.select(other, "skeleton");
                other.teleportTo(owner.getX() - 2, owner.getY(), owner.getZ());
                var zombie = summon(test, owner, false, false);
                zombie.setTarget(owner);
                test.assertTrue(zombie.getTarget() == null, "Companions cannot target their owner");
                zombie.setTarget(other);
                test.assertTrue(zombie.getTarget() == null, "Companions do not automatically hunt monster players");
                MonsterCompanions.onOwnerAttack(owner, other);
                test.assertTrue(zombie.getTarget() == other, "A successful-owner-attack order permits a monster target");
                zombie.setTarget(null);
                var cow = EntityTypes.COW.create(owner.level(), EntitySpawnReason.COMMAND);
                cow.snapTo(owner.getX(), owner.getY(), owner.getZ() + 2, 0, 0);
                owner.level().addFreshEntity(cow);
                test.assertTrue(cow.hurtServer(owner.level(), owner.damageSources().mobAttack(MonsterRoles.nativeModel(owner)), 1), "Owner deals actual damage");
                test.assertTrue(zombie.getTarget() == cow, "AFTER_DAMAGE orders companions to the owner's ordinary-mob target");
                cow.discard();
            } finally { clean(owner); clean(other); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void conversionAndInfectionKeepImmutableOwnerAndCombatOrigin(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var zombie = summon(test, owner, false, false);
                var frozen = new FrozenMonsterSource(UUID.randomUUID(), owner.getUUID(), 2, 3, true);
                ((MonsterSourceHolder) zombie).hardrush$stampMonsterSource(frozen);
                var origin = new CombatOrigin(frozen.roundId(), UUID.randomUUID(),
                        new CombatActor(owner.getUUID(), ParticipantRole.MONSTER_DEPLOYED, 2, 3),
                        AttackKind.SUMMON, Optional.of(zombie.getUUID()), List.of());
                ((CombatOriginHolder) zombie).hardrush$sealCombatOrigin(Optional.of(origin));
                var drowned = zombie.convertTo(EntityTypes.DROWNED, ConversionParams.single(zombie, true, true), ignored -> {});
                test.assertTrue(drowned != null && owner.getUUID().equals(MonsterCompanions.owner(drowned)), "Water conversion retains owner");
                test.assertTrue(MonsterCombatRuntime.source(drowned).orElseThrow().equals(frozen), "Conversion preserves frozen P2 source");
                var childOrigin = CombatRuntime.originOf(drowned).orElseThrow();
                test.assertTrue(childOrigin.actor().equals(origin.actor()) && childOrigin.directEntityId().orElseThrow().equals(drowned.getUUID()), "Converted P1 source keeps identity and binds its new entity ID");
                var villager = EntityTypes.VILLAGER.create(owner.level(), EntitySpawnReason.COMMAND);
                villager.snapTo(owner.getX(), owner.getY(), owner.getZ() + 2, 0, 0);
                owner.level().addFreshEntity(villager);
                test.assertTrue(drowned.convertVillagerToZombieVillager(owner.level(), villager), "Native villager infection remains enabled");
                var infected = owner.level().getEntitiesOfClass(ZombieVillager.class, owner.getBoundingBox().inflate(8)).stream()
                        .filter(mob -> owner.getUUID().equals(MonsterCompanions.owner(mob))).findFirst().orElseThrow();
                test.assertTrue(MonsterCombatRuntime.source(infected).orElseThrow().equals(frozen), "Infection inherits the attacker's immutable source");
                test.assertTrue(CombatRuntime.originOf(infected).orElseThrow().actor().equals(origin.actor()), "Infection preserves P1 actor");
                var cured = infected.convertTo(EntityTypes.VILLAGER, ConversionParams.single(infected, true, true), ignored -> {});
                try {
                    test.assertTrue(cured != null && MonsterCompanions.owner(cured) == null
                            && MonsterCompanions.event(cured) == null, "Curing removes all companion ownership");
                    test.assertTrue(MonsterCombatRuntime.source(cured).isEmpty() && CombatRuntime.originOf(cured).isEmpty(), "Curing ends the inherited combat lineage");
                    MonsterRoles.clear(owner);
                    test.assertTrue(cured.isAlive() && !MonsterCompanions.isDismissing(cured), "An ordinary cured villager survives the owner's departure");
                } finally { if (cured != null) cured.discard(); }
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void ordinaryDeathDropsEquipmentButDismissalAndOldLoadsDoNot(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var human = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var ordinary = summon(test, owner, false, false);
                ordinary.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
                ordinary.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                test.assertTrue(ordinary.hurtServer(owner.level(), human.damageSources().playerAttack(human), 1000), "Ordinary lethal combat succeeds");
                test.assertTrue(owner.level().getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(8)).stream()
                        .anyMatch(item -> item.getItem().is(Items.DIAMOND_SWORD)), "Native equipment drop remains enabled");
                ordinary.discard();
                for (var item : owner.level().getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(8))) item.discard();
                var dismissed = summon(test, owner, false, false);
                dismissed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
                dismissed.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                var unloaded = EntityTypes.ZOMBIE.create(owner.level(), EntitySpawnReason.COMMAND);
                MonsterCompanions.inherit(dismissed, unloaded);
                unloaded.snapTo(owner.getX(), owner.getY(), owner.getZ() + 3, 0, 0);
                MonsterRoles.clear(owner);
                test.assertTrue(dismissed.isDeadOrDying() && MonsterCompanions.isDismissing(dismissed), "Leaving an outing immediately starts companion death");
                test.assertTrue(owner.level().getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(8)).isEmpty(), "Dismissal drops no equipment or loot");
                MonsterRoles.select(owner, "zombie");
                owner.level().addFreshEntity(unloaded);
                test.assertTrue(unloaded.isDeadOrDying() && MonsterCompanions.isDismissing(unloaded), "Loading a previous outing's companion rejects its old binding");
            } finally { clean(owner); clean(human); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void newReinforcementChickenInheritsButBorrowedChickenRemainsOrdinary(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            var borrowed = EntityTypes.CHICKEN.create(owner.level(), EntitySpawnReason.COMMAND);
            try {
                owner.level().getEntitiesOfClass(Chicken.class, owner.getBoundingBox().inflate(8)).forEach(Entity::discard);
                MonsterRoles.select(owner, "zombie");
                var ancestor = summon(test, owner, false, false);
                var frozen = new FrozenMonsterSource(UUID.randomUUID(), owner.getUUID(), 4, 5, true);
                ((MonsterSourceHolder) ancestor).hardrush$stampMonsterSource(frozen);
                var newborn = EntityTypes.ZOMBIE.create(owner.level(), EntitySpawnReason.REINFORCEMENT);
                newborn.snapTo(owner.getX() - 2, owner.getY(), owner.getZ(), 0, 0);
                MonsterCompanions.inherit(ancestor, newborn);
                // Native draws: handedness, pickup, reject existing-chicken branch, accept new-chicken branch.
                newborn.finalizeSpawn(spawnLevel(owner, .5f, .5f, .5f, 0),
                        owner.level().getCurrentDifficultyAt(newborn.blockPosition()), EntitySpawnReason.REINFORCEMENT,
                        new Zombie.ZombieGroupData(true, true));
                owner.level().addFreshEntity(newborn);
                test.assertTrue(newborn.getVehicle() instanceof Chicken, "The native reinforcement path creates its jockey mount");
                var generated = (Chicken) newborn.getVehicle();
                test.assertValueEqual(MonsterCompanions.owner(generated), owner.getUUID(), "A newly created mount inherits the sortie owner");
                test.assertValueEqual(MonsterCompanions.event(generated), MonsterCompanions.event(ancestor), "A new mount keeps the ancestor event");
                test.assertValueEqual(MonsterCombatRuntime.source(generated).orElseThrow(), frozen, "The mount preserves its ancestor's frozen combat source");
                borrowed.snapTo(owner.getX(), owner.getY(), owner.getZ() + 3, 0, 0);
                owner.level().addFreshEntity(borrowed);
                var rider = EntityTypes.ZOMBIE.create(owner.level(), EntitySpawnReason.REINFORCEMENT);
                rider.snapTo(owner.getX(), owner.getY(), owner.getZ() + 2, 0, 0);
                MonsterCompanions.inherit(ancestor, rider);
                rider.finalizeSpawn(spawnLevel(owner, .5f, .5f, 0),
                        owner.level().getCurrentDifficultyAt(rider.blockPosition()), EntitySpawnReason.REINFORCEMENT,
                        new Zombie.ZombieGroupData(true, true));
                owner.level().addFreshEntity(rider);
                test.assertTrue(rider.getVehicle() == borrowed, "The alternate native branch borrows an existing chicken");
                test.assertTrue(MonsterCompanions.owner(borrowed) == null, "Borrowing never takes ownership of an ordinary chicken");
                MonsterRoles.clear(owner);
                test.assertTrue(generated.isDeadOrDying() && MonsterCompanions.isDismissing(generated), "A generated mount is cleaned up with the whole sortie");
                test.assertTrue(borrowed.isAlive() && !MonsterCompanions.isDismissing(borrowed), "An existing ordinary mount survives departure");
            } finally { borrowed.discard(); clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void distantCompanionFollowsIntoLoadedSafeSpace(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var zombie = summon(test, owner, false, false);
                ((MonsterMobAccessor) zombie).hardrush$targetSelector().removeAllGoals(ignored -> true);
                zombie.setNoGravity(true);
                zombie.snapTo(owner.getX() + 18, owner.getY(), owner.getZ(), 0, 0);
                test.runAfterDelay(20, () -> {
                    try {
                        test.assertTrue(zombie.isAlive() && zombie.distanceToSqr(owner) < 64, "Follow goal reaches a safe nearby loaded position");
                        test.assertTrue(zombie.getTarget() == null, "Following never turns owner into a combat target");
                        test.succeed();
                    } finally { clean(owner); }
                });
            } catch (RuntimeException failure) { clean(owner); throw failure; }
        });
    }

    private static Zombie summon(GameTestHelper test, ServerPlayer owner, boolean baby, boolean drowned) {
        var floor = owner.blockPosition().east(2).below();
        owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterCompanions.egg(owner, baby, drowned));
        long before = owner.level().getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(8)).stream()
                .filter(zombie -> owner.getUUID().equals(MonsterCompanions.owner(zombie))).count();
        test.assertTrue(MonsterCompanions.useOn(owner, InteractionHand.MAIN_HAND, hit(floor)), "Bound egg spawns on a safe floor");
        var all = owner.level().getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(8)).stream()
                .filter(zombie -> owner.getUUID().equals(MonsterCompanions.owner(zombie))).toList();
        test.assertValueEqual((long) all.size(), before + 1, "Each egg creates exactly one owned zombie");
        return all.getLast();
    }
    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false);
    }
    private static ServerLevelAccessor spawnLevel(ServerPlayer owner, float... draws) {
        var random = new LegacyRandomSource(1) {
            private int index;
            @Override public float nextFloat() { return index < draws.length ? draws[index++] : .99f; }
        };
        return (ServerLevelAccessor) Proxy.newProxyInstance(ServerLevelAccessor.class.getClassLoader(),
                new Class<?>[]{ServerLevelAccessor.class}, (proxy, method, args) ->
                        method.getName().equals("getRandom") ? random : method.invoke(owner.level(), args));
    }
    private static void clean(ServerPlayer player) {
        for (var entity : player.level().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(24)))
            if (!(entity instanceof ServerPlayer) && (player.getUUID().equals(MonsterCompanions.owner(entity)) || entity instanceof ItemEntity)) entity.discard();
        MonsterRoleGameTest.remove(player);
    }
}
