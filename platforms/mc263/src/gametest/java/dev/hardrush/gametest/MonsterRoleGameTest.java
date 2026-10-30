package dev.hardrush.gametest;

import dev.hardrush.disguise.MonsterDisguise;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.monster.MonsterItems;
import dev.hardrush.monster.MonsterCompanions;
import java.util.List;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exercises real role hooks/packets and native combat; transport is simulated. */
public final class MonsterRoleGameTest {
    private static final java.util.concurrent.atomic.AtomicInteger ARENA = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.Map<java.util.UUID,EmbeddedChannel> CHANNELS=new java.util.HashMap<>();
    private record ForcedChunk(net.minecraft.server.level.ServerLevel level,net.minecraft.world.level.ChunkPos pos) {}
    private static final java.util.Map<java.util.UUID,List<ForcedChunk>> FORCED=new java.util.HashMap<>();
    private static final java.util.Map<ForcedChunk,Integer> FORCE_REFERENCES=new java.util.HashMap<>();

    @GameTest(maxTicks=20)
    public void slainMonsterBookIsANamedNativeEnchantedBookFromRandomLoot(GameTestHelper test) {
        var level = test.getLevel();
        for (int cost : new int[] {20, 30}) {
            var book = dev.hardrush.monster.MonsterWorthRewards.signedBook(level, "Hunter", level.getRandom(), cost);
            test.assertTrue(book.is(Items.ENCHANTED_BOOK), "Vanilla enchantItem turns the book into an enchanted book");
            var stored = book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
            test.assertTrue(stored != null && !stored.isEmpty(), "A level " + cost + " roll always stores an enchantment");
            for (var enchantment : stored.keySet()) {
                test.assertTrue(enchantment.is(net.minecraft.tags.EnchantmentTags.ON_RANDOM_LOOT),
                        "Every enchantment comes from #on_random_loot");
            }
            test.assertValueEqual(book.getHoverName().getString(), "从 Hunter 身上找到的书", "The book names whose body it came from");
            test.assertFalse(book.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME), "The name is the item name, not an italic rename");
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void nativeStatsBoundBirthItemsAndHumanRestoration(GameTestHelper test) {
        var player=actor(test);
        player.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));
        player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
        player.setHealth(17); player.getFoodData().setFoodLevel(13);
        double originalSpeed=player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
        try {
            for (String kind:List.of("zombie","skeleton","creeper")) {
                MonsterRoles.select(player,kind);
                var model=MonsterRoles.nativeModel(player);
                test.assertValueEqual(player.getMaxHealth(),model.getMaxHealth(),"Health is from native species");
                test.assertValueEqual(player.getHealth(),model.getMaxHealth(),"Role starts with native health");
                test.assertValueEqual(player.getAttribute(Attributes.ARMOR).getBaseValue(),model.getAttribute(Attributes.ARMOR).getBaseValue(),"Intrinsic armor is native, separate from equipment");
                for (int slot=0;slot<player.getInventory().getContainerSize();slot++) {
                    var item=player.getInventory().getItem(slot);
                    test.assertTrue(item.isEmpty() || MonsterItems.owned(player,item),"Every generated item is bound to this body");
                }
                double speed=model.getAttributeValue(Attributes.MOVEMENT_SPEED);
                test.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED)-speed*speed)<.00001,"Native AI movement is translated to player input");
                test.assertValueEqual(player.gameMode.getGameModeForPlayer(),GameType.ADVENTURE,"Role cannot use creative player abilities");
                player.getInventory().setItem(0,new ItemStack(Items.ENDER_PEARL));
                UseItemCallback.EVENT.invoker().interact(player,player.level(),InteractionHand.MAIN_HAND);
                test.assertTrue(player.getMainHandItem().is(Items.ENDER_PEARL) && !player.isUsingItem(),"World items remain in inventory without granting human item-use abilities");
                player.stopUsingItem();
            }
            player.setHealth(7);
            MonsterRoles.onDisconnect(player);
            MonsterRoles.onJoin(player);
            test.assertValueEqual(player.getHealth(),7f,"Reconnect does not heal a monster by restoring human escrow health");
            MonsterRoles.clear(player);
            test.assertTrue(player.getMainHandItem().is(Items.DIAMOND_SWORD) && player.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),"Probe clear restores escrowed human equipment");
            test.assertValueEqual(player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(),originalSpeed,"Human movement restored");
            test.assertValueEqual(player.getHealth(),17f,"Human health restored");
            test.assertValueEqual(player.getFoodData().getFoodLevel(),13,"Human food restored");
            test.assertTrue(!MonsterRoles.isMonster(player),"Role identity cleared");
        } finally { remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void inventoryPacketsKeepBoundEggsButAllowWorldPickupAndStorage(GameTestHelper test) {
        var player=actor(test);
        var floor=player.blockPosition().below();
        ItemEntity item=null;
        try {
            MonsterRoles.select(player,"zombie");
            var egg=player.getInventory().getItem(1).copy();
            for(var action:List.of(ServerboundPlayerActionPacket.Action.DROP_ITEM,ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
                player.getInventory().setSelectedSlot(1);
                player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action,floor,Direction.UP,0));
                test.assertTrue(ItemStack.matches(player.getInventory().getItem(1),egg),"Bound birth eggs cannot be dropped");
                test.assertTrue(player.level().getBlockState(floor).is(Blocks.SMOOTH_STONE),"Role cannot destroy floor");
            }
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,floor,Direction.UP,0));
            test.assertTrue(ItemStack.matches(player.getOffhandItem(),egg),"Bound controls can move to the offhand");
            player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,floor,Direction.UP,0));
            player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36,new ItemStack(Items.BOW)));
            test.assertTrue(ItemStack.matches(player.getMainHandItem(),egg),"Creative slot packet cannot inject a bow");
            item=new ItemEntity(player.level(),player.getX(),player.getY(),player.getZ(),new ItemStack(Items.DIAMOND));
            player.level().addFreshEntity(item); item.setNoPickUpDelay(); item.playerTouch(player);
            test.assertTrue(item.isRemoved(),"World items enter the real inventory");
            player.getInventory().setSelectedSlot(0);
            var chest=player.blockPosition().offset(2,0,0);
            player.level().setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            var interaction=UseBlockCallback.EVENT.invoker().interact(player,player.level(),InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chest),Direction.UP,chest,false));
            test.assertTrue(interaction==net.minecraft.world.InteractionResult.PASS,"Storage containers use the vanilla interaction path");
            test.assertTrue(!MonsterRoles.burst(player),"Zombie cannot use skeleton arrows");
            test.assertTrue(player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(10)).isEmpty(),"Zombie has no ordinary bow path");
        } finally { if(item!=null)item.discard(); remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=300)
    public void nativeMeleeHasOneTargetAndUsesSelectedRealWeapon(GameTestHelper test) {
        var zombie=actor(test); var human=actor(test);var expectedTarget=actor(test);
        human.teleportTo(zombie.getX(),zombie.getY(),zombie.getZ()+1.0);
        MonsterRoles.select(zombie,"zombie");
        drop(zombie,new ItemStack(Items.DIAMOND_SWORD)).playerTouch(zombie);
        zombie.getInventory().setItem(4,take(zombie,Items.DIAMOND_SWORD));
        zombie.getInventory().setSelectedSlot(4);
        MonsterRoles.syncNativeContext(zombie);
        expectedTarget.teleportTo(zombie.getX()-1,zombie.getY(),zombie.getZ());
        human.setGameMode(GameType.SURVIVAL);
        test.runAfterDelay(80,()->{
            try {
                float before=human.getHealth();
                var model=MonsterRoles.nativeModel(zombie);
                model.doHurtTarget(zombie.level(),expectedTarget);
                test.assertTrue(MonsterRoles.attack(zombie,human),"Native zombie melee damages a human");
                test.assertTrue(human.getHealth()<before,"Exactly the native attack is applied");
                test.assertValueEqual(human.getHealth(),expectedTarget.getHealth(),"Selected real weapon matches native mob damage");
                float after=human.getHealth();
                test.assertTrue(!MonsterRoles.attack(zombie,human) && human.getHealth()==after,"Native 20-tick melee interval prevents spam");
                var pig=EntityTypes.PIG.create(zombie.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                pig.snapTo(zombie.position().add(0,0,1)); zombie.level().addFreshEntity(pig);
                test.assertTrue(MonsterRoles.legalTarget(zombie.getUUID(),zombie.level(),pig),"Native animals are legal player-monster targets");
                test.assertTrue(!MonsterRoles.attack(zombie,pig),"Changing to an animal target cannot bypass the melee cooldown"); pig.discard();
                MonsterRoles.select(zombie,"skeleton");
                test.assertTrue(!MonsterRoles.attack(zombie,human),"A bow-holding skeleton cannot fall back to player melee");
                MonsterRoles.select(zombie,"creeper");
                test.assertTrue(!MonsterRoles.attack(zombie,human),"Creeper cannot perform player melee");
            } finally {
                remove(zombie);remove(human);remove(expectedTarget);
                for(var item:zombie.level().getEntitiesOfClass(ItemEntity.class,zombie.getBoundingBox().inflate(5)))item.discard();
            }
            test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void consumedEggsRemainConsumedWhenTheSameBodyReconnects(GameTestHelper test) {
        var owner=actor(test);
        MonsterRoles.select(owner,"zombie");
        var children=new java.util.ArrayList<Zombie>();
        test.runAfterDelay(10,()->{
        cleanArena(owner);
        try {
            owner.getInventory().setSelectedSlot(1);
            var floor=owner.blockPosition().offset(2,-1,0);
            test.assertTrue(MonsterCompanions.useOn(owner,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor),Direction.UP,floor,false)),"One selected baby egg creates one native mob");
            for(var child:owner.level().getEntitiesOfClass(Zombie.class,owner.getBoundingBox().inflate(8))) {
                if(owner.getUUID().equals(MonsterRoles.summonOwner(child))) children.add(child);
            }
            test.assertValueEqual(children.size(),1,"Exactly one baby from one egg");
            for(var child:children) {
                test.assertTrue(child.isBaby() && !child.canPickUpLoot(),"Native baby, no equipment pickup");
                test.assertValueEqual(child.getMaxHealth(),20f,"Native zombie health, not old custom 12HP");
                child.setTarget(owner); test.assertTrue(child.getTarget()==null,"Cannot attack summoner");
            }
            test.assertTrue(owner.getInventory().getItem(1).isEmpty(),"The successful egg is consumed");
            var adult=owner.getInventory().getItem(2).copy();
            MonsterRoles.onDisconnect(owner); MonsterRoles.onJoin(owner);
            test.assertTrue(owner.getInventory().getItem(1).isEmpty(),"Reconnect cannot refill a consumed egg");
            test.assertTrue(ItemStack.matches(owner.getInventory().getItem(2),adult),"Reconnect preserves the remaining adult egg");
            test.assertTrue(children.stream().allMatch(child->!child.isAlive()),"Disconnect dismisses the old body's companions");
        } finally { children.forEach(Entity::discard); remove(owner); }
        test.succeed();
        });
    }

    @GameTest(maxTicks=1200)
    public void skeletonBurstIsThreeNativeUnpickableArrows(GameTestHelper test) {
        var player=actor(test);
        MonsterRoles.select(player,"skeleton");
        test.assertTrue(MonsterRoles.burst(player),"Three-arrow burst starts");
        test.assertTrue(!MonsterRoles.burst(player),"Duplicate activation is rejected");
        test.startSequence().thenWaitUntil(()->test.assertTrue(MonsterRoles.status(player).contains("arrowsFired=3"),"All three timed shots fired"))
            .thenExecute(()->{
                try {
                    test.assertTrue(MonsterRoles.status(player).contains("burstRemaining=0"),"Burst has ended");
                    for(var arrow:player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(50))) {
                        if(arrow.getOwner()==player) {
                            test.assertValueEqual(arrow.pickup,AbstractArrow.Pickup.DISALLOWED,"Cannot farm arrows");
                            test.assertTrue(!arrow.isCritArrow(),"Uses native mob arrow, no player full-draw critical");
                            var source=MonsterRoles.nativeArrowShooter(arrow,player);
                            test.assertTrue(!(source instanceof Player),"Difficulty source remains a native skeleton");
                        }
                    }
                    MonsterRoles.clear(player); MonsterRoles.select(player,"skeleton");
                    test.assertTrue(!MonsterRoles.burst(player),"Burst cooldown remains across human switch");
                } finally {
                    for(var arrow:player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(80))) if(arrow.getOwner()==player)arrow.discard();
                    remove(player);
                }
            }).thenSucceed();
    }

    @GameTest(maxTicks=200)
    public void movementUsesNativeMobAccelerationRatherThanRawAttribute(GameTestHelper test) {
        var player=actor(test);
        try {
            for(String kind:List.of("zombie","skeleton","creeper")) {
                MonsterRoles.select(player,kind);
                var mob=MonsterRoles.nativeModel(player);
                mob.setYRot(0); player.setYRot(0);
                mob.setDeltaMovement(Vec3.ZERO); player.setDeltaMovement(Vec3.ZERO);
                mob.setSpeed((float)mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
                player.setSpeed((float)player.getAttributeValue(Attributes.MOVEMENT_SPEED));
                mob.moveRelative(mob.getSpeed(),new Vec3(0,0,mob.zza));
                player.moveRelative(player.getSpeed(),new Vec3(0,0,1));
                test.assertTrue(mob.getDeltaMovement().distanceTo(player.getDeltaMovement())<.000001,
                    "Actual native movement accelerator agrees for AI and player input");
            }
        } finally { remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=400)
    public void normalSkeletonBowIsRoleControlledAndRequiresNativeDraw(GameTestHelper test) {
        var player=actor(test);
        MonsterRoles.select(player,"skeleton"); player.getInventory().setSelectedSlot(0);
        test.assertTrue(MonsterRoles.useControl(player,InteractionHand.MAIN_HAND),"Role bow starts drawing");
        test.runAfterDelay(5,()->{
            player.releaseUsingItem();
            test.assertTrue(MonsterRoles.status(player).contains("arrowsFired=0"),"Cannot shoot before native 20-tick draw");
            test.assertTrue(MonsterRoles.useControl(player,InteractionHand.MAIN_HAND),"Bow can be drawn again");
            test.runAfterDelay(21,()->{
                try {
                    player.releaseUsingItem();
                    test.assertTrue(MonsterRoles.status(player).contains("arrowsFired=1"),"Native release hook makes exactly one role arrow");
                    test.assertTrue(player.getInventory().getItem(8).isEmpty(),"Infinite default arrows do not create a hidden inventory ammo stack");
                } finally {
                    for(var arrow:player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(50))) if(arrow.getOwner()==player)arrow.discard();
                    remove(player);
                }
                test.succeed();
            });
        });
    }

    @GameTest(maxTicks=1200)
    public void creeperUsesNativeBlastWithoutBlocksOrIllegalVictims(GameTestHelper test) {
        var creeper=actor(test); var human=actor(test);
        human.teleportTo(creeper.getX()+1.5,creeper.getY(),creeper.getZ());
        var pig=EntityTypes.PIG.create(creeper.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        pig.setNoAi(true); pig.snapTo(creeper.position().add(-1.5,0,0)); creeper.level().addFreshEntity(pig);
        var floor=creeper.blockPosition().below(); float pigHealth=pig.getHealth();
        MonsterRoles.select(creeper,"creeper");
        test.runAfterDelay(80,()->{
            double walkingSpeed=creeper.getAttributeValue(Attributes.MOVEMENT_SPEED);
            test.assertTrue(MonsterRoles.ignite(creeper),"Native creeper fuse starts");
                test.assertTrue(MonsterRoles.ignite(creeper),"Repeated held input keeps the same native fuse active");
            test.assertTrue(walkingSpeed>0,"Creeper walking speed is positive");
            test.assertValueEqual(creeper.getAttributeValue(Attributes.MOVEMENT_SPEED),walkingSpeed,"Priming and repeated ignition preserve normal walking speed");
            test.startSequence().thenWaitUntil(()->test.assertTrue(!creeper.isAlive(),"Blast ends the prototype body"))
                .thenExecute(()->{
                    try {
                        test.assertTrue(human.getHealth()<20 || !human.isAlive(),"Legal human receives native blast damage");
                        test.assertTrue(pig.getHealth()<pigHealth,"Native animals receive the native blast damage");
                        test.assertTrue(creeper.level().getBlockState(floor).is(Blocks.SMOOTH_STONE),"Blocks are preserved");
                        var loot=creeper.level().getEntitiesOfClass(ItemEntity.class,creeper.getBoundingBox().inflate(5));
                        test.assertTrue(loot.stream().noneMatch(item -> item.getItem().is(Items.GUNPOWDER)),"Bound role controls do not drop as loot");
                    } finally { pig.discard(); remove(creeper); remove(human); }
                }).thenSucceed();
        });
    }

    @GameTest(maxTicks=200)
    public void realEquipmentMovesInternallyAndConservesWorldItems(GameTestHelper test) {
        var player=actor(test); var observer=actor(test);
        var items=new java.util.ArrayList<ItemEntity>();
        whenArenaTicking(test,player,()->{
        cleanArena(player);
        try {
            MonsterRoles.select(player,"zombie");
            var sword=drop(player,new ItemStack(Items.DIAMOND_SWORD));items.add(sword);sword.playerTouch(player);
            test.assertTrue(sword.isRemoved(),"Accepted world weapon is consumed once");
            player.getInventory().setItem(4,take(player,Items.DIAMOND_SWORD)); player.getInventory().setSelectedSlot(4);
            MonsterRoles.repairLoadout(player);
            test.assertTrue(MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD),"Selected inventory weapon is the actual equipment");
            test.assertTrue(MonsterRoles.nativeModel(player).getAttributeValue(Attributes.ATTACK_DAMAGE)>3,"Native weapon modifiers apply to combat model");
            test.assertTrue(Math.abs(player.getAttributeValue(Attributes.ATTACK_SPEED)-1)<.00001,"Weapon modifiers cannot freeze melee indicator");
            var helmet=drop(player,new ItemStack(Items.IRON_HELMET));items.add(helmet);helmet.playerTouch(player);
            player.setItemSlot(EquipmentSlot.HEAD,take(player,Items.IRON_HELMET)); MonsterRoles.repairLoadout(player);
            test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),"Explicitly equipped armor supplies physical protection");
            var worse=drop(player,new ItemStack(Items.LEATHER_HELMET));items.add(worse);worse.playerTouch(player);
            test.assertTrue(worse.isRemoved() && player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),"Picking up weaker armor stores it without changing the equipped helmet");
            player.getInventory().setSelectedSlot(1);
            var packet=new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(player.getId(),List.of(
                com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND,player.getMainHandItem())));
            MonsterRoles.repairLoadout(player);
            test.assertTrue(Math.abs(player.getAttributeValue(Attributes.ATTACK_SPEED)-1)<.00001,"Skill control preserves melee indicator");
            var rewritten=(net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket)MonsterDisguise.rewrite(observer,packet);
            test.assertTrue(rewritten.getSlots().stream().anyMatch(pair->pair.getFirst()==EquipmentSlot.MAINHAND && ItemStack.matches(pair.getSecond(),player.getMainHandItem())),"Observer sees the selected real item including a moved control");
            player.getInventory().setSelectedSlot(4); MonsterRoles.repairLoadout(player);
            MonsterRoles.onDisconnect(player);MonsterRoles.onJoin(player);
            player.getInventory().setSelectedSlot(4);MonsterRoles.repairLoadout(player);
            test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD),"Gear survives model rebinding");
            MonsterRoles.dropRoleEquipment(player,true);MonsterRoles.dropRoleEquipment(player,true);
            var returned=player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(2));
            test.assertValueEqual(returned.stream().filter(e->e.getItem().is(Items.DIAMOND_SWORD)).mapToInt(e->e.getItem().getCount()).sum(),1,"World sword returns once");
            test.assertValueEqual(returned.stream().filter(e->e.getItem().is(Items.IRON_HELMET)).mapToInt(e->e.getItem().getCount()).sum(),1,"World helmet returns once");
        } finally {
            items.forEach(Entity::discard);
            remove(player);remove(observer);
            for(var item:player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(5)))item.discard();
        }
        test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void skeletonStoresWorldWeaponsAndUsesSelectedEnchantedBow(GameTestHelper test) {
        var player=actor(test);var items=new java.util.ArrayList<ItemEntity>();
        MonsterRoles.select(player,"skeleton");
        whenArenaTicking(test,player,()->{
        cleanArena(player);
        try {
            var sword=drop(player,new ItemStack(Items.NETHERITE_SWORD));items.add(sword);sword.playerTouch(player);
            test.assertTrue(sword.isRemoved() && MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.BOW),"Picking up a sword stores it and preserves the selected bow");
            var bow=new ItemStack(Items.BOW);
            bow.enchant(player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER),3);
            var expectedBow=bow.copy();
            var enchanted=drop(player,bow);items.add(enchanted);enchanted.playerTouch(player);
            test.assertTrue(enchanted.isRemoved(),"The world bow enters the real inventory");
            for(int slot=0;slot<36;slot++) if (ItemStack.matches(player.getInventory().getItem(slot),expectedBow)) player.getInventory().setSelectedSlot(slot);
            MonsterRoles.repairLoadout(player);
            MonsterRoles.burst(player);
            var arrows=player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(3));
            test.assertTrue(arrows.stream().anyMatch(a->a.getOwner()==player && ItemStack.matches(a.getWeaponItem(),expectedBow)),"Native arrow carries real enchanted weapon");
            MonsterRoles.dropRoleEquipment(player,true);
            test.assertValueEqual(player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(3)).stream()
                .filter(e->e.getItem().is(Items.BOW)).count(),1L,"Only world bow returns; generated bow never drops");
        } finally {
            items.forEach(Entity::discard);
            for(var e:player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(20)))if(e.getOwner()==player)e.discard();
            remove(player);
            for(var e:player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(5)))e.discard();
        }
        test.succeed();
        });
    }

    @GameTest(maxTicks=200)
    public void undeadBreathingEffectsAndSmiteUseNativeSpecies(GameTestHelper test) {
        var player=actor(test);var human=actor(test);
        try {
            var sword=new ItemStack(Items.IRON_SWORD);
            sword.enchant(player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SMITE),2);
            for(String kind:List.of("zombie","skeleton","creeper")) {
                MonsterRoles.select(player,kind);
                var mob=MonsterRoles.nativeModel(player);
                test.assertValueEqual(player.canBreatheUnderwater(),mob.canBreatheUnderwater(),"Breathing matches native species");
                for(var effect:List.of(net.minecraft.world.effect.MobEffects.POISON,net.minecraft.world.effect.MobEffects.REGENERATION)) {
                    var potion=new net.minecraft.world.effect.MobEffectInstance(effect,200);
                    test.assertValueEqual(player.canBeAffected(potion),mob.canBeAffected(potion),"Potion immunity matches native species");
                }
                var source=player.damageSources().playerAttack(human);
                float nativeDamage=net.minecraft.world.item.enchantment.EnchantmentHelper.modifyDamage(player.level(),sword,mob,source,7);
                float roleDamage=net.minecraft.world.item.enchantment.EnchantmentHelper.modifyDamage(player.level(),sword,player,source,7);
                test.assertValueEqual(roleDamage,nativeDamage,"Smite predicates use role species");
                test.assertValueEqual(roleDamage,kind.equals("creeper")?7f:12f,"Smite bonus applies to undead roles only");
            }
            MonsterRoles.clear(player);test.assertTrue(!player.canBreatheUnderwater(),"Human breathing restores after clear");
        } finally { remove(player);remove(human); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void daylightNeverIgnitesPlayerRolesOrWearsTheirHelmets(GameTestHelper test) {
        var player=actor(test);
        var clock=player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
            .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        long daytime=player.level().clockManager().getInstance(clock).totalTicks();
        var weather=player.level().getWeatherData();
        boolean raining=weather.isRaining(),thundering=weather.isThundering();
        int clearTime=weather.getClearWeatherTime(),rainTime=weather.getRainTime(),thunderTime=weather.getThunderTime();
        weather.setClearWeatherTime(6000);weather.setRaining(false);weather.setThundering(false);
        player.level().setBlockAndUpdate(player.blockPosition().above(3),Blocks.AIR.defaultBlockState());
        player.level().clockManager().setTotalTicks(clock,6000);
        whenArenaTicking(test,player,()->{
          cleanArena(player);
          try {
            test.assertTrue(player.level().canSeeSky(player.blockPosition()), "The daylight control is exposed to the sky");
            for(String kind:List.of("zombie","skeleton","creeper")) {
                MonsterRoles.select(player,kind);
                for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET))player.setItemSlot(slot,ItemStack.EMPTY);
                player.clearFire();
                for(int i=0;i<500;i++)MonsterRoles.updateEnvironment(player);
                test.assertTrue(!player.isOnFire(), "Bareheaded player role never ignites in sunlight: "+kind);
                if(!kind.equals("creeper")) {
                    var stack=new ItemStack(Items.IRON_HELMET);
                    stack.setDamageValue(stack.getMaxDamage()-1);
                    int originalDamage=stack.getDamageValue();
                    var helmet=drop(player,stack);
                    try {
                        helmet.playerTouch(player);
                        player.setItemSlot(EquipmentSlot.HEAD,take(player,Items.IRON_HELMET));
                        test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "Control helmet is equipped");
                        for(int i=0;i<1500;i++)MonsterRoles.updateEnvironment(player);
                        var equipped=player.getItemBySlot(EquipmentSlot.HEAD);
                        test.assertTrue(equipped.is(Items.IRON_HELMET) && equipped.getDamageValue()==originalDamage,
                                "Sunlight neither wears nor breaks a player monster's helmet: "+kind);
                    } finally { helmet.discard(); }
                }
                player.setRemainingFireTicks(100);
                MonsterRoles.updateEnvironment(player);
                test.assertTrue(player.getRemainingFireTicks()==100, "Existing environmental fire is preserved: "+kind);
                player.setInvulnerableTime(0);
                player.damageCooldownTime=0;
                float health=player.getHealth();
                player.hurtServer(player.level(),player.damageSources().onFire(),1);
                test.assertTrue(player.getHealth()<health, "Disabling daylight ignition grants no fire-damage immunity: "+kind);
                player.damageCooldownTime=0;
                health=player.getHealth();
                player.hurtServer(player.level(),player.damageSources().lava(),4);
                test.assertTrue(player.getHealth()<health, "Lava damage still applies to every player role: "+kind);
                player.clearFire();
            }
          } finally {
            player.level().clockManager().setTotalTicks(clock,daytime);
            weather.setClearWeatherTime(clearTime);weather.setRainTime(rainTime);weather.setThunderTime(thunderTime);
            weather.setRaining(raining);weather.setThundering(thundering);
            remove(player);
          }
          test.succeed();
        });
    }


    @GameTest(maxTicks=200)
    public void armorProtectsWithoutPlayerWearAndGearDoesNotGrantHumanAbilities(GameTestHelper test) {
        var player=actor(test);var human=actor(test);
        MonsterRoles.select(player,"zombie");
        test.runAfterDelay(80,()->{
            cleanArena(player);
            try {
                drop(player,new ItemStack(Items.IRON_HELMET)).playerTouch(player);
                player.setItemSlot(EquipmentSlot.HEAD,take(player,Items.IRON_HELMET));
                test.assertTrue(player.getAttributeValue(Attributes.ARMOR)>=2,"Native intrinsic armor remains");
                // Equipment synchronization is normally performed by the next
                // entity tick; let native Player update its physical attributes.
                test.runAfterDelay(2,()->{
                    try {
                        test.assertTrue(player.getAttributeValue(Attributes.ARMOR)>2,"World helmet adds real physical armor");
                        int wear=player.getItemBySlot(EquipmentSlot.HEAD).getDamageValue();
                        float before=player.getHealth();
                        player.hurtServer(player.level(),player.damageSources().playerAttack(human),6);
                        test.assertTrue(player.getHealth()<before && player.getHealth()>before-6,"Actual vanilla armor reduces damage");
                        test.assertValueEqual(player.getItemBySlot(EquipmentSlot.HEAD).getDamageValue(),wear,"Mob armor avoids player impact wear");
                        drop(player,new ItemStack(Items.ELYTRA)).playerTouch(player);
                        player.setItemSlot(EquipmentSlot.CHEST,take(player,Items.ELYTRA));
                        test.assertTrue(player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),"Native zombie can wear elytra as gear");
                        player.setOnGround(false);player.setDeltaMovement(0,-.5,0);
                        test.assertTrue(!player.tryToStartFallFlying(),"Equipping elytra does not grant monster flight");
                        var sword=new ItemStack(Items.DIAMOND_SWORD);
                        sword.enchant(player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.VANISHING_CURSE),1);
                        drop(player,sword).playerTouch(player);
                        MonsterRoles.dropRoleEquipment(player,true);
                        test.assertTrue(player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(3)).stream()
                            .noneMatch(e->e.getItem().is(Items.DIAMOND_SWORD)),"Vanishing applies to world gear on death");
                        MonsterRoles.clear(player);
                        player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));
                        player.setOnGround(false);player.setDeltaMovement(0,-.5,0);
                        test.assertTrue(player.tryToStartFallFlying(),"Human gliding remains available after role clear");
                    } finally {
                        remove(player);remove(human);
                        for(var item:player.level().getEntitiesOfClass(ItemEntity.class,player.getBoundingBox().inflate(5)))item.discard();
                    }
                    test.succeed();
                });
            } catch(RuntimeException error) { remove(player);remove(human);throw error; }
        });
    }

    private static void cleanArena(ServerPlayer player) {
        // The GameTest run directory can retain these private arenas across
        // processes. Clear old projectiles/items/mobs after entity loading.
        for(var entity:player.level().getEntitiesOfClass(Entity.class,player.getBoundingBox().inflate(8)))
            if(!(entity instanceof ServerPlayer))entity.discard();
    }

    private static ItemEntity drop(ServerPlayer player,ItemStack stack) {
        var item=new ItemEntity(player.level(),player.getX(),player.getY(),player.getZ(),stack);
        item.setNoPickUpDelay();player.level().addFreshEntity(item);return item;
    }

    private static ItemStack take(ServerPlayer player,net.minecraft.world.item.Item item) {
        for(int slot=0;slot<36;slot++) if(player.getInventory().getItem(slot).is(item) && !MonsterItems.bound(player.getInventory().getItem(slot)))
            return player.getInventory().removeItemNoUpdate(slot);
        throw new IllegalStateException("Missing picked-up item "+item);
    }

    /** Forced arena chunks load asynchronously; item-entity queries skip sections that are not yet accessible. */
    public static void whenArenaTicking(GameTestHelper test, ServerPlayer player, Runnable action) {
        var level=player.level();var center=player.blockPosition();
        test.startSequence().thenIdle(10).thenWaitUntil(()->{
            for(int dx=-8;dx<=8;dx+=16)for(int dz=-8;dz<=8;dz+=16)
                test.assertTrue(level.isPositionEntityTicking(center.offset(dx,0,dz)),"Actor arena entities are ticking");
        }).thenExecute(action);
    }

    public static ServerPlayer actor(GameTestHelper test) {
        int center=1000+ARENA.getAndIncrement()*24;
        var server=test.getLevel().getServer();
        var profile=new GameProfile(java.util.UUID.randomUUID(),"HRRole"+center);
        var player=new ServerPlayer(server,test.getLevel(),profile,ClientInformation.createDefault());
        var connection=new SinkConnection();
        CHANNELS.put(player.getUUID(),new EmbeddedChannel(connection));
        dev.hardrush.gametest.UnmanagedProfileFixtures.run(() ->
                server.getPlayerList().placeNewPlayer(connection,player,CommonListenerCookie.createInitial(profile,false)));
        for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++) {
            test.getLevel().setBlockAndUpdate(new BlockPos(center+dx,149,dz),Blocks.SMOOTH_STONE.defaultBlockState());
            for(int y=150;y<=154;y++)test.getLevel().setBlockAndUpdate(new BlockPos(center+dx,y,dz),Blocks.AIR.defaultBlockState());
        }
        player.teleportTo(center+.5,150,.5);
        var forced=new java.util.ArrayList<ForcedChunk>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            var chunk=new net.minecraft.world.level.ChunkPos((player.getBlockX()>>4)+dx,(player.getBlockZ()>>4)+dz);
            var key=new ForcedChunk(player.level(),chunk);forced.add(key);
            if(FORCE_REFERENCES.merge(key,1,Integer::sum)==1)player.level().setChunkForced(chunk.x(),chunk.z(),true);
        }
        FORCED.put(player.getUUID(),forced);
        test.getLevel().setBlockAndUpdate(player.blockPosition().above(3),Blocks.BARRIER.defaultBlockState());
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        return player;
    }
    public static void remove(ServerPlayer player) {
        MonsterRoles.clear(player); MonsterDisguise.clear(player);
        player.level().getServer().getPlayerList().remove(player);
        var channel=CHANNELS.remove(player.getUUID()); if(channel!=null)channel.finishAndReleaseAll();
        var forced=FORCED.remove(player.getUUID());if(forced!=null)for(var key:forced) {
            int references=FORCE_REFERENCES.get(key)-1;
            if(references==0) { FORCE_REFERENCES.remove(key);key.level().setChunkForced(key.pos().x(),key.pos().z(),false); }
            else FORCE_REFERENCES.put(key,references);
        }
    }
    private static final class SinkConnection extends Connection {
        SinkConnection(){super(PacketFlow.SERVERBOUND);}
        @Override public void send(Packet<?> packet){}
        @Override public void send(Packet<?> packet,ChannelFutureListener listener){}
        @Override public void send(Packet<?> packet,ChannelFutureListener listener,boolean flush){}
    }
}
