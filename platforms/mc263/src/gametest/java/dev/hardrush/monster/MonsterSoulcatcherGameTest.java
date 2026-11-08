package dev.hardrush.monster;

import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.gametest.MonsterRoleGameTest;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Headless soulcatcher catch, release and inventory rules; outside a round the bag is common with three places. */
public final class MonsterSoulcatcherGameTest {
    @GameTest(maxTicks = 200)
    public void catchesUpToCapacityAndReleasesTheSameNativeBody(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var bag = holdBag(test, owner);
                test.assertTrue(bag.is(Items.DYED_BUNDLE.pick(net.minecraft.world.item.DyeColor.WHITE)), "A new sortie carries a common soulcatcher");
                var wounded = spawn(owner, EntityTypes.HUSK, 2);
                wounded.setHealth(7);
                wounded.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, wounded), "A husk fits a common bag");
                test.assertTrue(wounded.isRemoved(), "The caught body leaves the world");
                for (int i = 0; i < 2; i++) {
                    test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, spawn(owner, EntityTypes.ZOMBIE, 3 + i)),
                            "Three places take three creatures");
                }
                var overflow = spawn(owner, EntityTypes.ZOMBIE, 5);
                test.assertTrue(!MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, overflow), "A full bag refuses a fourth");
                test.assertTrue(overflow.isAlive() && !overflow.isRemoved(), "A refused creature stays in the world");
                var contents = contents(owner);
                // Identical bodies stack natively, like any bundle; the husk keeps its own egg.
                test.assertValueEqual(contents.itemCopies().mapToInt(ItemStack::getCount).sum(), 3, "Three bodies make three eggs");
                test.assertValueEqual(contents.size(), 2, "Two identical zombies share one stack");
                test.assertTrue(contents.weight().getOrThrow().intValue() == 1, "Native bundle weight shows the bag full");
                test.assertTrue(contents.itemCopies().allMatch(egg -> egg.getMaxStackSize() == 3), "Each egg weighs one place");

                // Select the oldest egg (the husk) the way the native hover-scroll does.
                var selected = contents.asMutable();
                selected.toggleSelectedItem(1);
                owner.getMainHandItem().set(DataComponents.BUNDLE_CONTENTS, selected.toImmutable());
                var floor = owner.blockPosition().east(2).below();
                test.assertTrue(MonsterSoulcatcher.releaseOn(owner, InteractionHand.MAIN_HAND, hit(floor)), "The selected egg lands on the clicked face");
                var released = owner.level().getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(8)).stream()
                        .filter(mob -> owner.getUUID().equals(MonsterCompanions.owner(mob))).toList();
                test.assertValueEqual(released.size(), 1, "One release creates one body");
                var husk = released.getFirst();
                test.assertTrue(husk.getType() == EntityTypes.HUSK && husk.getHealth() == 7
                        && husk.getMainHandItem().is(Items.IRON_SWORD), "Health and equipment survive the bag");
                test.assertTrue(!husk.getUUID().equals(wounded.getUUID()) && husk.isPersistenceRequired(), "A fresh, persistent body");
                test.assertTrue(MonsterCompanions.hatchedBaby(husk).isEmpty(), "A caught body never folds into a role egg");
                test.assertValueEqual(contents(owner).itemCopies().mapToInt(ItemStack::getCount).sum(), 2, "Release frees one place");
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, husk), "An own companion goes back into the bag");
                test.assertTrue(!MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, overflow), "The bag is full again");
                overflow.discard();
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void tiersRefuseUnreachableSpeciesAndOrdinaryCreaturesStayNative(GameTestHelper test) {
        test.assertTrue(MonsterSoulcatcher.rule(EntityTypes.ENDER_DRAGON).isEmpty(), "The dragon is never caught");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.ZOMBIE_VILLAGER).orElseThrow(), new MonsterSoulcatcher.Catch(0, true), "Zombie kin start common");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.SKELETON).orElseThrow(), new MonsterSoulcatcher.Catch(1, true), "Skeletons need uncommon");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.PHANTOM).orElseThrow(), new MonsterSoulcatcher.Catch(1, false), "Phantoms fly on their own");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.CREEPER).orElseThrow(), new MonsterSoulcatcher.Catch(2, true), "Creepers need rare");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.PIGLIN).orElseThrow(), new MonsterSoulcatcher.Catch(2, false), "Brain-driven piglins stay native");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.RAVAGER).orElseThrow(), new MonsterSoulcatcher.Catch(3, true), "Ravagers need epic");
        test.assertValueEqual(MonsterSoulcatcher.rule(EntityTypes.WARDEN).orElseThrow(), new MonsterSoulcatcher.Catch(3, false), "Bosses stay native");
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                holdBag(test, owner);
                var skeleton = spawn(owner, EntityTypes.SKELETON, 2);
                test.assertTrue(!MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, skeleton), "A common bag refuses a skeleton");
                test.assertTrue(skeleton.isAlive() && !skeleton.isRemoved(), "The refused skeleton stays");
                var cow = spawn(owner, EntityTypes.COW, 3);
                test.assertTrue(!MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, cow), "Passive creatures need a rare bag");
                // A rare bag's catch, put in directly: it leaves as an ordinary native cow with no sortie lineage.
                var egg = MonsterSoulcatcher.egg(owner, cow, Items.COW_SPAWN_EGG);
                var bag = contents(owner).asMutable();
                bag.tryInsert(egg);
                owner.getMainHandItem().set(DataComponents.BUNDLE_CONTENTS, bag.toImmutable());
                cow.discard();
                test.assertTrue(MonsterSoulcatcher.releaseOn(owner, InteractionHand.MAIN_HAND, hit(owner.blockPosition().south(2).below())), "Release");
                var cows = owner.level().getEntitiesOfClass(Cow.class, owner.getBoundingBox().inflate(8));
                test.assertValueEqual(cows.size(), 1, "One cow returns");
                test.assertTrue(MonsterCompanions.owner(cows.getFirst()) == null && cows.getFirst().isPersistenceRequired(),
                        "An ordinary creature has no owner and never despawns");
                skeleton.discard(); cows.getFirst().discard();
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void hatchedZombiesFoldBackIntoTheirEggAndOthersCompanionsAreRefused(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var rival = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                MonsterRoles.select(rival, "zombie");
                rival.teleportTo(owner.getX() - 3, owner.getY(), owner.getZ());
                int before = eggs(owner, "adult_egg");
                owner.setItemInHand(InteractionHand.MAIN_HAND, owner.getInventory().getItem(2).copyWithCount(1));
                owner.getInventory().getItem(2).shrink(1);
                test.assertTrue(MonsterCompanions.useOn(owner, InteractionHand.MAIN_HAND, hit(owner.blockPosition().east(2).below())), "Hatch");
                var hatched = owned(owner).getFirst();
                test.assertTrue(MonsterCompanions.hatchedBaby(hatched).equals(java.util.Optional.of(false)), "The body remembers its adult egg");
                holdBag(test, owner);
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, hatched), "The bag folds it back");
                test.assertValueEqual(eggs(owner, "adult_egg"), before, "It returns to the adult egg");
                test.assertTrue(contents(owner).isEmpty(), "A hatched zombie never takes a bag place");

                rival.setItemInHand(InteractionHand.MAIN_HAND, MonsterCompanions.egg(rival, false, false));
                test.assertTrue(MonsterCompanions.useOn(rival, InteractionHand.MAIN_HAND, hit(rival.blockPosition().west(2).below())), "Rival hatch");
                var foreign = owned(rival).getFirst();
                test.assertTrue(!MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, foreign), "Another monster's companion is refused");
                test.assertTrue(foreign.isAlive(), "It stays with its owner");
            } finally { clean(owner); clean(rival); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void nothingGoesInOrComesOutByHandAndWaterTakesARelease(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            var water = owner.blockPosition().east(2);
            try {
                MonsterRoles.select(owner, "zombie");
                var menu = owner.inventoryMenu;
                int bagSlot = 36 + 3; // hotbar slot 3 in the player inventory menu
                test.assertTrue(MonsterSoulcatcher.isBag(menu.slots.get(bagSlot).getItem()), "The bag sits in hotbar slot 4");
                test.assertTrue(!MonsterInventory.mayClick(owner, menu, bagSlot, 1, ContainerInput.PICKUP), "A right click cannot pull an egg out");
                menu.setCarried(new ItemStack(Items.DIRT));
                test.assertTrue(!MonsterInventory.mayClick(owner, menu, bagSlot, 0, ContainerInput.PICKUP), "A held stack cannot go in");
                menu.setCarried(menu.slots.get(bagSlot).getItem().copy());
                menu.slots.get(9).set(new ItemStack(Items.DIRT));
                test.assertTrue(!MonsterInventory.mayClick(owner, menu, 9, 0, ContainerInput.PICKUP), "The held bag cannot scoop a slot");
                test.assertTrue(!MonsterInventory.mayClick(owner, menu, 10, 1, ContainerInput.PICKUP), "The held bag cannot drop an egg out");
                menu.setCarried(ItemStack.EMPTY);
                test.assertTrue(MonsterInventory.mayClick(owner, menu, bagSlot, 0, ContainerInput.PICKUP), "The bag itself still moves");
                menu.slots.get(9).set(ItemStack.EMPTY);

                holdBag(test, owner);
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, spawn(owner, EntityTypes.DROWNED, 3)), "Catch");
                owner.level().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
                owner.setYRot(-90); owner.setYHeadRot(-90); owner.setXRot(40);
                test.assertTrue(MonsterSoulcatcher.releaseInLiquid(owner, InteractionHand.MAIN_HAND), "A water surface takes the release");
                test.assertValueEqual(owned(owner).size(), 1, "The drowned returns as a companion");
                owner.setXRot(-90);
                test.assertTrue(!MonsterSoulcatcher.releaseInLiquid(owner, InteractionHand.MAIN_HAND), "Plain air never releases");
            } finally {
                owner.level().setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
                clean(owner);
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aNativeRightClickOnACreatureCatchesIt(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                holdBag(test, owner);
                for (boolean sneaking : new boolean[] {false, true}) {
                    var target = spawn(owner, EntityTypes.ZOMBIE, 2);
                    // A vanilla click is one positioned interaction, then the air use the client sends after it.
                    owner.connection.handleInteract(new ServerboundInteractPacket(target.getId(), InteractionHand.MAIN_HAND,
                            new Vec3(0, 1, 0), sneaking));
                    owner.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 0, 0, 0));
                    test.assertTrue(target.isRemoved(), "A right-click on a creature catches it, sneaking " + sneaking);
                }
                test.assertValueEqual(contents(owner).itemCopies().mapToInt(ItemStack::getCount).sum(), 2,
                        "Each click makes one egg and the trailing air use releases nothing");
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void companionBlastsReachTheirOwnSideButNeverAimThere(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                var creeper = (Creeper) spawn(owner, EntityTypes.CREEPER, 3);
                MonsterCompanions.adopt(owner, creeper);
                var ally = spawn(owner, EntityTypes.ZOMBIE, 4);
                MonsterCompanions.adopt(owner, ally);
                test.assertTrue(MonsterCombatRuntime.reject(creeper, owner), "A companion never aims at its owner");
                test.assertTrue(MonsterCombatRuntime.reject(creeper, ally), "Nor at an allied companion");
                test.assertTrue(!MonsterCombatRuntime.rejectExplosion(creeper, owner), "Its blast still reaches the owner natively");
                test.assertTrue(!MonsterCombatRuntime.rejectExplosion(creeper, ally), "And the owner's other companions");
                creeper.discard(); ally.discard();
            } finally { clean(owner); }
            test.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aPausedBodyKeepsTheBagAndItsCatch(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(80, () -> {
            try {
                MonsterRoles.select(owner, "zombie");
                holdBag(test, owner);
                test.assertTrue(MonsterSoulcatcher.capture(owner, InteractionHand.MAIN_HAND, spawn(owner, EntityTypes.ZOMBIE_VILLAGER, 3)), "Catch");
                var text = MonsterParking.capture(owner);
                test.assertTrue(!text.isEmpty(), "A bag with a catch fits the round record");
                var body = MonsterParking.decode(owner, text, dev.hardrush.core.round.MonsterRole.ZOMBIE).orElseThrow();
                var parked = body.items().values().stream().filter(MonsterSoulcatcher::isBag).findFirst().orElseThrow();
                var refreshed = MonsterSoulcatcher.refresh(owner, parked);
                var eggs = refreshed.get(DataComponents.BUNDLE_CONTENTS).itemCopies().toList();
                test.assertValueEqual(eggs.size(), 1, "The catch survives the pause");
                test.assertTrue(eggs.getFirst().is(Items.ZOMBIE_VILLAGER_SPAWN_EGG) && MonsterItems.owned(owner, eggs.getFirst())
                        && MonsterItems.owned(owner, refreshed), "Bag and eggs are rebound to this deployment");
            } finally { clean(owner); }
            test.succeed();
        });
    }

    private static ItemStack holdBag(GameTestHelper test, ServerPlayer owner) {
        var inventory = owner.getInventory();
        test.assertTrue(MonsterItems.matches(owner, inventory.getItem(3), MonsterSoulcatcher.PURPOSE), "A zombie sortie carries a bound soulcatcher");
        inventory.setSelectedSlot(3);
        return owner.getMainHandItem();
    }
    private static BundleContents contents(ServerPlayer owner) {
        return owner.getMainHandItem().getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
    }
    private static Mob spawn(ServerPlayer owner, EntityType<? extends Mob> type, int offset) {
        var mob = type.create(owner.level(), EntitySpawnReason.COMMAND);
        mob.snapTo(owner.getX() + offset, owner.getY(), owner.getZ() + 1, 0, 0);
        mob.setNoAi(true);
        owner.level().addFreshEntity(mob);
        return mob;
    }
    private static List<Mob> owned(ServerPlayer owner) {
        return owner.level().getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(12)).stream()
                .filter(mob -> owner.getUUID().equals(MonsterCompanions.owner(mob))).toList();
    }
    private static int eggs(ServerPlayer owner, String purpose) {
        int count = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
            var stack = owner.getInventory().getItem(i);
            if (MonsterItems.matches(owner, stack, purpose)) count += stack.getCount();
        }
        return count;
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
