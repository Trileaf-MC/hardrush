package dev.hardrush.monster;

import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.monster.mixin.MonsterEntityAccessor;
import dev.hardrush.monster.mixin.MonsterSkeletonAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ConversionTracker;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.effect.MobEffects;
import java.util.List;

/** In-process native menus and bodies, without a graphical client. */
public final class MonsterInventoryFormGameTest {
    @GameTest(maxTicks=200)
    public void boundItemsMoveInsideButCannotEscapeThroughMenuInputs(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            var egg = p.getInventory().getItem(1).copy();
            var storage = new SimpleContainer(27);
            var menu = ChestMenu.threeRows(1, p.getInventory(), storage);
            p.containerMenu = menu;
            test.assertTrue(MonsterInventory.mayClick(p, menu, 55, 0, ContainerInput.PICKUP), "Own bound item may be picked up in a chest menu");
            menu.clicked(55, 0, ContainerInput.PICKUP, p);
            test.assertTrue(MonsterItems.matches(p, menu.getCarried(), "baby_egg"), "Actual native pickup retains the binding");
            for (var input : List.of(ContainerInput.PICKUP, ContainerInput.QUICK_CRAFT))
                test.assertFalse(MonsterInventory.mayClick(p, menu, 0, 1, input), "Bound cursor cannot enter external storage");
            menu.clicked(0, 0, ContainerInput.PICKUP, p);
            test.assertTrue(storage.isEmpty() && ItemStack.matches(menu.getCarried(), egg), "Rejected external pickup changes neither stack");
            menu.clicked(56, 0, ContainerInput.PICKUP, p);
            test.assertTrue(MonsterItems.matches(p, p.getInventory().getItem(2), "baby_egg"), "An internal swap moves the control");
            // Return the swapped adult egg to the now-empty original slot.
            menu.clicked(55, 0, ContainerInput.PICKUP, p);
            test.assertFalse(MonsterInventory.mayClick(p, menu, 56, 0, ContainerInput.QUICK_MOVE), "Shift click cannot export born inventory");
            test.assertFalse(MonsterInventory.mayClick(p, menu, 56, 0, ContainerInput.THROW), "Throw cannot export born inventory");
            test.assertFalse(MonsterInventory.mayClick(p, menu, 0, 2, ContainerInput.SWAP), "Hotbar swap cannot export born inventory");
            p.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 7));
            menu.clicked(59, 0, ContainerInput.QUICK_MOVE, p);
            test.assertValueEqual(storage.getItem(0).getCount(), 7, "World resources use native container transfer");
            test.assertTrue(p.getInventory().getItem(5).isEmpty(), "Exactly seven diamonds leave the inventory");
            p.containerMenu = p.inventoryMenu;
            test.assertFalse(MonsterInventory.mayClick(p, p.inventoryMenu, 1, 0, ContainerInput.PICKUP), "Personal crafting stays disabled");
        } finally { p.containerMenu = p.inventoryMenu; MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void closingFullInventoryRetainsBoundCursorAndSettlementDropsWorldItemsOnce(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(10, () -> {
        try {
            MonsterRoles.select(p, "zombie");
            var control = p.getInventory().getItem(7).copy();
            for (int i=0;i<36;i++) p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            p.inventoryMenu.setCarried(control);
            p.inventoryMenu.removed(p);
            test.assertTrue(p.inventoryMenu.getCarried().isEmpty(), "Closing clears the cursor");
            test.assertValueEqual(countPurpose(p, "return"), 1, "Full inventory receives exactly one bound control");
            test.assertValueEqual(dropped(p, Items.COBBLESTONE), 64, "Only one world stack spills to make room");
            MonsterRoles.dropRoleEquipment(p, false);
            MonsterRoles.dropRoleEquipment(p, false);
            test.assertValueEqual(dropped(p, Items.COBBLESTONE), 36*64, "Exit returns the exact original world quantity once");
            test.assertValueEqual(dropped(p, Items.ECHO_SHARD), 0, "Bound control never becomes world loot");
        } finally { cleanup(p); MonsterRoleGameTest.remove(p); }
        test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void firstEntryEscrowsOnlyItemsLeftAfterNativeContainerClose(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(10, () -> {
            try {
                for (int i=0;i<36;i++) p.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
                p.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND,7));
                p.inventoryMenu.slots.get(1).set(new ItemStack(Items.EMERALD,5));
                MonsterRoles.select(p,"zombie");
                test.assertValueEqual(dropped(p,Items.DIAMOND),7,"Native close spills the full cursor once");
                test.assertValueEqual(dropped(p,Items.EMERALD),5,"Native close spills full-inventory crafting inputs once");
                MonsterRoles.clear(p); MonsterRoles.clear(p);
                test.assertValueEqual(possessed(p,Items.COBBLESTONE),36*64,"Human escrow restores the original full inventory exactly");
                test.assertValueEqual(possessed(p,Items.DIAMOND)+dropped(p,Items.DIAMOND),7,"Spilled cursor is not also restored from escrow");
                test.assertValueEqual(possessed(p,Items.EMERALD)+dropped(p,Items.EMERALD),5,"Spilled crafting input is not also restored from escrow");
                cleanup(p);
                p.containerMenu=ChestMenu.threeRows(1,p.getInventory(),new SimpleContainer(27));
                p.containerMenu.setCarried(new ItemStack(Items.DIAMOND,7));
                MonsterRoles.select(p,"zombie"); MonsterRoles.clear(p);
                test.assertValueEqual(possessed(p,Items.COBBLESTONE),36*64,"External menu close also preserves the original inventory");
                test.assertValueEqual(possessed(p,Items.DIAMOND)+dropped(p,Items.DIAMOND),7,"External menu cursor is not duplicated into escrow");
            } finally { cleanup(p); MonsterRoleGameTest.remove(p); }
            test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void equippedItemsComeFromSelectedSlotsAndCreeperArmorHasNoEffect(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            p.getInventory().setItem(4, new ItemStack(Items.DIAMOND_AXE));
            p.getInventory().setSelectedSlot(4);
            p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            MonsterRoles.repairLoadout(p);
            var model = MonsterRoles.nativeModel(p);
            test.assertTrue(model.getMainHandItem().is(Items.DIAMOND_AXE) && model.getOffhandItem().is(Items.SHIELD)
                    && model.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "Native model mirrors all actual equipment slots");
            p.getInventory().setSelectedSlot(7); MonsterRoles.repairLoadout(p);
            test.assertTrue(model.getMainHandItem().is(Items.ECHO_SHARD), "A stored weapon is no longer secretly equipped");
            MonsterRoles.select(p, "creeper");
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            p.getInventory().setItem(4, new ItemStack(Items.DIAMOND_SWORD)); p.getInventory().setSelectedSlot(4);
            MonsterRoles.repairLoadout(p);
            test.assertTrue(p.getItemBySlot(EquipmentSlot.CHEST).isEmpty() && p.getArmorValue()==0, "Creeper removes externally equipped armor before combat");
            test.assertTrue(MonsterRoles.nativeModel(p).getMainHandItem().isEmpty(), "Creeper never borrows weapon attributes or visual equipment");
            test.assertFalse(MonsterInventory.mayClick(p,p.inventoryMenu,6,0,ContainerInput.PICKUP), "Creeper armor menu rejects equip");
        } finally { cleanup(p); MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void powderSnowConversionResetsOnExitAndPreservesBodyInventory(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        test.runAfterDelay(10, () -> {
        try {
            MonsterRoles.select(p,"skeleton");
            var fresh=nativeConversionBody(p,false);
            var freshTracker=nativeTracker(fresh);
            setExposure(p,fresh,false,true);
            compareConversionTicks(test,p,fresh,freshTracker,441,MonsterForm.STRAY,true);
            cleanupConvertedBodies(p);
            MonsterRoles.select(p, "skeleton"); p.setHealth(9);
            var nativeBody=nativeConversionBody(p,false);
            var tracker=nativeTracker(nativeBody);
            var binding = MonsterRoles.binding(p);
            p.getInventory().setItem(4,new ItemStack(Items.DIAMOND,13));
            setExposure(p,nativeBody,false,true);
            compareConversionTicks(test,p,nativeBody,tracker,440,MonsterForm.STRAY,false);
            setExposure(p,nativeBody,false,false); MonsterRoles.updateEnvironment(p); tracker.tick();
            test.assertFalse(tracker.isConverting(),"Native powder snow exit cancels conversion");
            setExposure(p,nativeBody,false,true);
            compareConversionTicks(test,p,nativeBody,tracker,442,MonsterForm.STRAY,true);
            test.assertValueEqual(MonsterRoles.currentForm(p),MonsterForm.STRAY,"Exact exposure converts to Stray");
            test.assertValueEqual(p.getHealth(),9f,"Conversion never heals");
            test.assertValueEqual(MonsterRoles.binding(p),binding,"Conversion retains sortie identity");
            test.assertValueEqual(p.getInventory().getItem(4).getCount(),13,"Conversion preserves actual inventory");
            test.assertFalse(p.canFreeze(),"Skeleton forms inherit native freeze immunity");
            var originalLevel=p.level();var originalPosition=p.position();
            var nether=originalLevel.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            p.teleportTo(nether,.5,150,.5,java.util.Set.of(),0,0,true);
            MonsterRoles.tick(originalLevel.getServer());
            test.assertValueEqual(MonsterRoles.currentForm(p),MonsterForm.STRAY,"Dimension rebinding preserves the converted form");
            test.assertTrue(MonsterRoles.nativeModel(p).level()==nether,"The converted native model belongs to the new dimension");
            test.assertValueEqual(MonsterRoles.binding(p),binding,"Dimension rebinding preserves sortie identity");
            test.assertValueEqual(p.getInventory().getItem(4).getCount(),13,"Dimension rebinding does not redraw inventory");
            test.assertValueEqual(p.getHealth(),9f,"Dimension rebinding does not heal");
            p.teleportTo(originalLevel,originalPosition.x,originalPosition.y,originalPosition.z,java.util.Set.of(),0,0,true);
            MonsterRoles.tick(originalLevel.getServer());
            MonsterRoles.clear(p); MonsterRoles.select(p,"skeleton");
            test.assertValueEqual(MonsterRoles.currentForm(p),MonsterForm.SKELETON,"A fresh outing resets the environmental form");
        } finally { p.isInPowderSnow=false; cleanupConvertedBodies(p); cleanup(p); MonsterRoleGameTest.remove(p); }
        test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void zombieWaterConversionConvertsOnlyRemainingEggs(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        var head = p.blockPosition().above();
        test.runAfterDelay(10, () -> {
        try {
            MonsterRoles.select(p,"zombie");
            var fresh=nativeConversionBody(p,true);
            var freshTracker=nativeTracker(fresh);
            setExposure(p,fresh,true,true);
            compareConversionTicks(test,p,fresh,freshTracker,901,MonsterForm.DROWNED,true);
            cleanupConvertedBodies(p);
            MonsterRoles.select(p,"zombie"); p.setHealth(8);
            var nativeBody=nativeConversionBody(p,true);
            var tracker=nativeTracker(nativeBody);
            p.getInventory().setItem(1,ItemStack.EMPTY);
            var adult=p.getInventory().getItem(2);p.getInventory().setItem(11,adult);p.getInventory().setItem(2,ItemStack.EMPTY);
            var binding=MonsterRoles.binding(p);
            setExposure(p,nativeBody,true,true);
            compareConversionTicks(test,p,nativeBody,tracker,900,MonsterForm.DROWNED,false);
            setExposure(p,nativeBody,true,false); MonsterRoles.updateEnvironment(p); tracker.tick();
            test.assertFalse(tracker.isConverting(),"Native water exit cancels conversion");
            setExposure(p,nativeBody,true,true);
            compareConversionTicks(test,p,nativeBody,tracker,902,MonsterForm.DROWNED,true);
            test.assertValueEqual(MonsterRoles.currentForm(p),MonsterForm.DROWNED,"Continuous submerged eyes produce drowned form");
            test.assertTrue(p.getInventory().getItem(11).is(Items.DROWNED_SPAWN_EGG),"Remaining egg changes at its actual slot");
            test.assertValueEqual(countPurpose(p,"baby_egg"),0,"Consumed egg never replenishes");
            test.assertValueEqual(countPurpose(p,"adult_egg"),1,"Adult egg count is preserved");
            test.assertValueEqual(MonsterRoles.binding(p),binding,"Water conversion keeps binding");
            test.assertValueEqual(p.getHealth(),8f,"Water conversion keeps health");
            test.assertFalse(p.getMainHandItem().is(Items.TRIDENT),"Player conversion does not grant a trident");
        } finally { p.level().setBlockAndUpdate(head,Blocks.AIR.defaultBlockState());p.level().setBlockAndUpdate(p.blockPosition(),Blocks.AIR.defaultBlockState());cleanupConvertedBodies(p);MonsterRoleGameTest.remove(p); }
        test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void ammunitionPriorityAndStrayEffectsUseNativeArrowFactory(GameTestHelper test) throws Exception {
        var p=MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p,"skeleton");
            p.getInventory().setItem(8,ItemStack.EMPTY);
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.ARROW) && p.getInventory().getItem(8).isEmpty(),"Fallback arrow remains virtual");
            p.getInventory().setItem(20,new ItemStack(Items.ARROW,4));
            p.getInventory().setItem(6,new ItemStack(Items.TIPPED_ARROW,3));
            p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.SPECTRAL_ARROW,2));
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.SPECTRAL_ARROW),"Offhand wins");
            p.setItemSlot(EquipmentSlot.OFFHAND,ItemStack.EMPTY);
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.TIPPED_ARROW),"Hotbar wins over main inventory");
            p.getInventory().setItem(6,ItemStack.EMPTY);
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.ARROW),"Main inventory is searched last");
            MonsterRoles.convert(p,MonsterForm.STRAY);
            var factory=(MonsterSkeletonAccessor)MonsterRoles.nativeModel(p);
            var getContents=Arrow.class.getDeclaredMethod("getPotionContents");getContents.setAccessible(true);
            var getScale=Arrow.class.getDeclaredMethod("getPotionDurationScale");getScale.setAccessible(true);
            var bow=new ItemStack(Items.BOW);
            var normal=factory.hardrush$getArrow(new ItemStack(Items.ARROW),1,bow);
            var contents=(PotionContents)getContents.invoke(normal);
            test.assertValueEqual((Float)getScale.invoke(normal),1f,"Normal arrow keeps the full 30-second native slowness duration");
            test.assertTrue(java.util.stream.StreamSupport.stream(contents.getAllEffects().spliterator(),false)
                    .anyMatch(e->e.is(MobEffects.SLOWNESS)&&e.getDuration()==600),"Normal stray arrow carries native 600-tick slowness");
            var tipped=new ItemStack(Items.TIPPED_ARROW,3);tipped.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.POISON));
            var poison=factory.hardrush$getArrow(tipped,1,bow);
            var potion=(PotionContents)getContents.invoke(poison);
            test.assertValueEqual((Float)getScale.invoke(poison),.125f,"Tipped arrow keeps the native one-eighth duration scale, including added slowness");
            test.assertTrue(java.util.stream.StreamSupport.stream(potion.getAllEffects().spliterator(),false).anyMatch(e->e.is(MobEffects.POISON)),"Tipped arrow preserves its potion");
            test.assertTrue(java.util.stream.StreamSupport.stream(potion.getAllEffects().spliterator(),false).anyMatch(e->e.is(MobEffects.SLOWNESS)),"Native stray adds slowness to tipped arrow");
            test.assertTrue(factory.hardrush$getArrow(new ItemStack(Items.SPECTRAL_ARROW),1,bow) instanceof SpectralArrow,"Spectral remains a spectral projectile");
            test.assertValueEqual(tipped.getCount(),3,"Arrow factory never consumes player ammunition");
            normal.discard();poison.discard();
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void worldDragCollectAndBoundArmorKeepNativeInventorySemantics(GameTestHelper test) {
        var p=MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p,"zombie");
            var armor=MonsterItems.bind(p,new ItemStack(Items.IRON_CHESTPLATE),"gear");
            p.inventoryMenu.setCarried(armor);
            p.inventoryMenu.clicked(6,0,ContainerInput.PICKUP,p);
            test.assertTrue(MonsterItems.owned(p,p.getItemBySlot(EquipmentSlot.CHEST)),"Born armor can be manually equipped from the cursor");
            p.inventoryMenu.clicked(6,0,ContainerInput.PICKUP,p);
            p.getInventory().setItem(4,new ItemStack(Items.BUNDLE));
            p.inventoryMenu.clicked(40,1,ContainerInput.PICKUP,p);
            test.assertTrue(MonsterItems.bound(p.inventoryMenu.getCarried()),"Right-click bundle cannot hide a born stack");
            test.assertFalse(MonsterInventory.mayClick(p,p.inventoryMenu,-999,0,ContainerInput.PICKUP),"Outside-click drop cannot export a born cursor");
            test.assertFalse(MonsterInventory.mayClick(p,p.inventoryMenu,40,0,ContainerInput.CLONE),"Creative clone is forbidden");
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            var storage=new SimpleContainer(27);var menu=ChestMenu.threeRows(1,p.getInventory(),storage);p.containerMenu=menu;
            menu.setCarried(new ItemStack(Items.EMERALD,8));
            menu.clicked(-999,0,ContainerInput.QUICK_CRAFT,p);
            menu.clicked(0,1,ContainerInput.QUICK_CRAFT,p);
            menu.clicked(1,1,ContainerInput.QUICK_CRAFT,p);
            menu.clicked(-999,2,ContainerInput.QUICK_CRAFT,p);
            test.assertValueEqual(storage.getItem(0).getCount(),4,"Native drag puts half the world stack in the first slot");
            test.assertValueEqual(storage.getItem(1).getCount(),4,"Native drag puts half the world stack in the second slot");
            test.assertTrue(menu.getCarried().isEmpty(),"World drag conserves the cursor quantity");
            menu.clicked(0,0,ContainerInput.PICKUP,p);
            menu.clicked(0,0,ContainerInput.PICKUP_ALL,p);
            test.assertValueEqual(menu.getCarried().getCount(),8,"Native double-click can gather world material");
            test.assertTrue(storage.isEmpty(),"Collected world stacks leave no duplicate behind");
            menu.setCarried(ItemStack.EMPTY);
        } finally { p.containerMenu=p.inventoryMenu;MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void creeperCarriesTotemWithoutAcquiringEquipmentDeathProtection(GameTestHelper test) {
        var p=MonsterRoleGameTest.actor(test);
        MonsterRoles.select(p,"creeper");
        p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        test.runAfterDelay(80,()->{
            try {
                p.setHealth(1);p.setInvulnerableTime(0);p.damageCooldownTime=0;
                test.assertTrue(p.hurtServer(p.level(),p.damageSources().generic(),100),"Ordinary lethal damage reaches the creeper");
                test.assertTrue(p.isDeadOrDying(),"Stored equipment does not activate a creeper totem");
                test.assertValueEqual(dropped(p,Items.TOTEM_OF_UNDYING),1,"The world totem returns intact with carried loot");
            } finally {cleanup(p);MonsterRoleGameTest.remove(p);}
            test.succeed();
        });
    }

    private static Mob nativeConversionBody(ServerPlayer p,boolean zombie) {
        Mob body=zombie ? new Zombie(p.level()) : new Skeleton(EntityTypes.SKELETON,p.level());
        body.setPos(p.position());body.setSilent(true);
        return body;
    }
    private static ConversionTracker<?> nativeTracker(Mob body) {
        try {
            var field=body.getClass().getDeclaredField(body instanceof Zombie ? "drowningTracker" : "freezingTracker");
            field.setAccessible(true);return (ConversionTracker<?>)field.get(body);
        } catch (ReflectiveOperationException error) {throw new AssertionError("Cannot read the vanilla entity's conversion tracker",error);}
    }
    private static void setExposure(ServerPlayer p,Mob body,boolean zombie,boolean exposed) {
        if (zombie) {
            var state=(exposed ? Blocks.WATER : Blocks.AIR).defaultBlockState();
            p.level().setBlockAndUpdate(p.blockPosition(),state);p.level().setBlockAndUpdate(p.blockPosition().above(),state);
            ((MonsterEntityAccessor)p).hardrush$updateFluidInteraction();((MonsterEntityAccessor)body).hardrush$updateFluidInteraction();
        } else {p.isInPowderSnow=exposed;body.isInPowderSnow=exposed;}
    }
    private static void compareConversionTicks(GameTestHelper test,ServerPlayer p,Mob body,ConversionTracker<?> tracker,
                                                int ticks,MonsterForm converted,boolean completes) {
        for (int tick=1;tick<=ticks;tick++) {
            MonsterRoles.updateEnvironment(p);tracker.tick();
            test.assertValueEqual(MonsterRoles.currentForm(p)==converted,body.isRemoved(),"Player conversion matches the native entity at tick "+tick);
            test.assertValueEqual(body.isRemoved(),completes && tick==ticks,"Native conversion reaches its exact boundary at tick "+tick);
        }
    }
    private static void cleanupConvertedBodies(ServerPlayer p) {
        p.level().getEntitiesOfClass(Mob.class,p.getBoundingBox().inflate(2)).stream()
                .filter(e->e.getType()==EntityTypes.STRAY || e.getType()==EntityTypes.DROWNED).forEach(Mob::discard);
    }
    private static int possessed(ServerPlayer p,net.minecraft.world.item.Item item) {
        int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();
        for(int i=1;i<=4;i++)if(p.inventoryMenu.slots.get(i).getItem().is(item))n+=p.inventoryMenu.slots.get(i).getItem().getCount();
        if(p.containerMenu.getCarried().is(item))n+=p.containerMenu.getCarried().getCount();return n;
    }
    private static int countPurpose(ServerPlayer p,String purpose) {int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(MonsterItems.matches(p,p.getInventory().getItem(i),purpose))n+=p.getInventory().getItem(i).getCount();return n;}
    private static int dropped(ServerPlayer p,net.minecraft.world.item.Item item) {return p.level().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(8)).stream().filter(e->e.getItem().is(item)).mapToInt(e->e.getItem().getCount()).sum();}
    private static void cleanup(ServerPlayer p) {p.level().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(8)).forEach(ItemEntity::discard);}
}
