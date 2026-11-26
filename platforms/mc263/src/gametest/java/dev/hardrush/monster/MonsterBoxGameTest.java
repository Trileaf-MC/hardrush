package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterBoxPool.Result;
import dev.hardrush.gametest.MonsterRoleGameTest;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Headless monster box rules (docs/plan/怪物盲盒.md); outside a round the soulcatcher, and so the box, is common. */
public final class MonsterBoxGameTest {
    @GameTest(maxTicks = 200)
    public void everyResultComesOutAsTheOwnersCompanions(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                for (var result : Result.values()) {
                    var root = MonsterBox.letOut(owner, landing(owner), result);
                    test.assertTrue(root != null, result + " comes out");
                    var bodies = root.getSelfAndPassengers().toList();
                    for (Entity body : bodies) {
                        test.assertTrue(owner.getUUID().equals(MonsterCompanions.owner(body)), result + ": every body is the owner's companion");
                        test.assertTrue(body instanceof Mob mob && mob.isPersistenceRequired() && !body.isRemoved(), result + ": persistent and in the world");
                    }
                    switch (result) {
                        case ZOMBIE -> test.assertTrue(root instanceof Zombie zombie && !zombie.isBaby() && bodies.size() == 1, "A plain adult zombie");
                        case BABY_ZOMBIE -> test.assertTrue(root instanceof Zombie zombie && zombie.isBaby() && bodies.size() == 1, "A baby zombie, alone");
                        case CHICKEN_JOCKEY -> test.assertTrue(root instanceof Chicken chicken && chicken.isChickenJockey()
                                && root.getFirstPassenger() instanceof Zombie rider && rider.isBaby(), "A jockey chicken carrying a baby zombie");
                        case ZOMBIE_HORSEMAN -> test.assertTrue(root.getType() == EntityTypes.ZOMBIE_HORSE
                                && root.getFirstPassenger() instanceof Zombie rider && rider.getMainHandItem().is(Items.IRON_SPEAR),
                                "A zombie horse ridden by a spear-armed zombie");
                        case SPIDER_JOCKEY -> test.assertTrue(root.getType() == EntityTypes.SPIDER
                                && root.getFirstPassenger() != null && root.getFirstPassenger().getType() == EntityTypes.SKELETON, "A skeleton riding a spider");
                        case WITHER -> test.assertTrue(root instanceof WitherBoss wither && wither.getInvulnerableTicks() == 220
                                && wither.getHealth() < wither.getMaxHealth() / 2, "The wither comes out charging from a third of its health");
                        case WARDEN -> test.assertTrue(root instanceof Warden warden && warden.hasPose(Pose.EMERGING), "The warden digs its way out");
                        default -> {}
                    }
                    bodies.forEach(Entity::discard);
                }
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aBoxIsSpentOnUseAndExplainsItself(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterBox.stack(owner, 2));
                var box = owner.getMainHandItem();
                test.assertValueEqual(box.getHoverName().getString(), "怪物盲盒", "The box names itself");
                test.assertTrue(box.get(DataComponents.RARITY) == Rarity.COMMON && Boolean.TRUE.equals(box.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                        "A common soulcatcher's box, glinting apart from the role's eggs");
                test.assertValueEqual(box.get(DataComponents.LORE).lines().getFirst().getString(), "升级拘魂袋，它能开出更疯狂的东西",
                        "Its one line points at the soulcatcher");
                test.assertTrue(MonsterBox.heldHint(owner, box).isPresent(), "Held, it tells how to use it");
                test.assertTrue(MonsterBox.useOn(owner, InteractionHand.MAIN_HAND, hit(owner.blockPosition().east(2).below())), "A box opens on a block");
                test.assertValueEqual(owner.getMainHandItem().getCount(), 1, "Opening spends one box");
                test.assertTrue(!owned(owner).isEmpty(), "What came out is the owner's");
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void onlyTheOwnerCarriesABoxCreatureAndItStaysTheirs(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var rival = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                MonsterRoles.select(rival, "zombie");
                rival.teleportTo(owner.getX() - 3, owner.getY(), owner.getZ());
                var wither = MonsterBox.letOut(owner, landing(owner), Result.WITHER);
                rival.getInventory().setSelectedSlot(3);
                test.assertTrue(!MonsterSoulcatcher.capture(rival, InteractionHand.MAIN_HAND, wither), "Another monster cannot catch the owner's box wither");
                owner.getInventory().setSelectedSlot(3);
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, wither), "Its owner takes it, even in a common bag");
                var eggs = owner.getMainHandItem().getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopies().toList();
                test.assertTrue(eggs.size() == 1 && eggs.getFirst().get(DataComponents.LORE).lines().get(1).getString().contains("你的随从"),
                        "The egg says it is still the owner's companion");
                test.assertTrue(MonsterSoulcatcher.releaseOn(owner, InteractionHand.MAIN_HAND, hit(owner.blockPosition().east(2).below())), "Release");
                var again = owned(owner).stream().filter(WitherBoss.class::isInstance).toList();
                test.assertValueEqual(again.size(), 1, "The wither comes back as the owner's companion, cleared when the owner leaves");
                again.forEach(Entity::discard);
            } finally { clean(owner); clean(rival); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aRefusedLandingKeepsTheBoxAndLetsNothingOut(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterBox.stack(owner, 2));
                // Far outside anything loaded: the refusal comes before the draw, so it cannot tell what was inside.
                var unloaded = new MonsterCompanions.Landing(new BlockPos(29_000_000, 64, 29_000_000), false, false);
                test.assertTrue(!MonsterCompanions.landable(owner, unloaded), "The fixture landing really is refused");
                test.assertTrue(!MonsterBox.open(owner, InteractionHand.MAIN_HAND, unloaded), "An unloaded landing refuses the box");
                test.assertValueEqual(owner.getMainHandItem().getCount(), 2, "A refused box is not spent");
                test.assertTrue(owned(owner).isEmpty(), "Nothing comes out anywhere");
            } finally { clean(owner); }
            test.succeed();
        });
    }

    /**
     * Wardens choose by anger (CompanionWardenMixin); the wither's spare heads and brain-driven breezes choose through
     * targeting conditions. Each spares its owner and the owner's allies yet still takes an iron golem, which all three
     * hunt natively (Breeze.canAttack admits only players and iron golems).
     */
    @GameTest(maxTicks = 200)
    public void bossesAndBrainsSpareTheOwnerAndAlliesButStillHunt(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var rival = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            Warden plain = null;
            Mob golem = null;
            try {
                MonsterRoles.select(owner, "zombie");
                MonsterRoles.select(rival, "zombie");
                var level = owner.level();
                rival.teleportTo(owner.getX() - 3, owner.getY(), owner.getZ());
                golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                golem.snapTo(owner.getX(), owner.getY(), owner.getZ() - 3, 0, 0);
                level.addFreshEntity(golem);
                plain = EntityTypes.WARDEN.create(level, EntitySpawnReason.COMMAND);
                plain.snapTo(owner.getX() - 3, owner.getY(), owner.getZ(), 0, 0);
                test.assertTrue(plain.canTargetEntity(owner), "A native warden would hunt the zombie player");

                var warden = (Warden) MonsterBox.letOut(owner, landing(owner), Result.WARDEN);
                test.assertTrue(!warden.canTargetEntity(owner), "The box warden never takes its owner as a target");
                test.assertTrue(!warden.canTargetEntity(rival), "Nor another monster player");
                test.assertTrue(warden.canTargetEntity(golem), "It still takes an iron golem");

                var conditions = TargetingConditions.forCombat().ignoreLineOfSight();
                for (var result : List.of(Result.WITHER, Result.BREEZE)) {
                    var mob = MonsterBox.letOut(owner, landing(owner), result);
                    test.assertTrue(!conditions.test(level, mob, owner), result + " never targets its owner");
                    test.assertTrue(!conditions.test(level, mob, rival), result + " never targets another monster player");
                    test.assertTrue(conditions.test(level, mob, golem), result + " still targets an iron golem");
                }
            } finally {
                if (plain != null) plain.discard();
                if (golem != null) golem.discard();
                clean(owner); clean(rival);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void theOwnerLeavingKillsBoxCreaturesWithoutLoot(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var chicken = MonsterBox.letOut(owner, landing(owner), Result.CHICKEN_JOCKEY);
                var rider = chicken.getFirstPassenger();
                MonsterCompanions.clearOwner(owner);
                test.assertTrue(chicken.isDeadOrDying() && rider instanceof Mob mob && mob.isDeadOrDying(), "Mount and rider die with the sortie");
                test.assertTrue(owner.level().getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(12)).isEmpty(),
                        "A cleared box creature drops nothing");
            } finally { clean(owner); }
            test.succeed();
        });
    }

    private static MonsterCompanions.Landing landing(ServerPlayer owner) {
        return MonsterCompanions.landingOn(owner, hit(owner.blockPosition().east(2).below()));
    }
    private static List<Mob> owned(ServerPlayer owner) {
        return owner.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(16)).stream()
                .filter(mob -> owner.getUUID().equals(MonsterCompanions.owner(mob))).toList();
    }
    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false);
    }
    private static void clean(ServerPlayer player) {
        for (Entity entity : player.level().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(24)))
            if (!(entity instanceof ServerPlayer) && (player.getUUID().equals(MonsterCompanions.owner(entity)) || entity instanceof ItemEntity)) entity.discard();
        MonsterRoleGameTest.remove(player);
    }
}
