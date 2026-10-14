package dev.hardrush.gametest;

import dev.hardrush.p0.disguise.DisguiseProbe;
import dev.hardrush.p0.monster.MonsterRoles;
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

    @GameTest(maxTicks=200)
    public void nativeStatsFixedLoadoutsAndHumanRestoration(GameTestHelper test) {
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
                test.assertValueEqual(player.getAttribute(Attributes.ARMOR).getBaseValue(),model.getAttributeValue(Attributes.ARMOR),"Intrinsic armor is native, not equipment");
                test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                    && player.getOffhandItem().isEmpty(),"No normal armor or shield");
                double speed=model.getAttributeValue(Attributes.MOVEMENT_SPEED);
                test.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED)-speed*speed)<.00001,"Native AI movement is translated to player input");
                test.assertValueEqual(player.gameMode.getGameModeForPlayer(),GameType.ADVENTURE,"Role cannot use creative player abilities");
                player.getInventory().setItem(0,new ItemStack(Items.ENDER_PEARL));
                UseItemCallback.EVENT.invoker().interact(player,player.level(),InteractionHand.MAIN_HAND);
                test.assertTrue(!player.getMainHandItem().is(Items.ENDER_PEARL),"Injected normal item never enters native use path");
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
    public void inventoryPacketsDropsPickupsAndContainersAreBlocked(GameTestHelper test) {
        var player=actor(test);
        var floor=player.blockPosition().below();
        ItemEntity item=null;
        try {
            MonsterRoles.select(player,"zombie");
            for(var action:List.of(ServerboundPlayerActionPacket.Action.DROP_ITEM,ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
                player.getInventory().setSelectedSlot(1);
                player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action,floor,Direction.UP,0));
                test.assertTrue(player.getInventory().getItem(1).is(Items.ROTTEN_FLESH) && player.getOffhandItem().isEmpty(),"Drop/swap cannot move fixed controls");
                test.assertTrue(player.level().getBlockState(floor).is(Blocks.SMOOTH_STONE),"Role cannot destroy floor");
            }
            for(var input:ContainerInput.values()) {
                player.connection.handleContainerClick(new ServerboundContainerClickPacket(0,player.inventoryMenu.getStateId(),(short)37,(byte)0,
                    input,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),HashedStack.EMPTY));
                test.assertTrue(player.getInventory().getItem(1).is(Items.ROTTEN_FLESH) && player.inventoryMenu.getCarried().isEmpty(),"All inventory input modes remain locked");
            }
            player.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36,new ItemStack(Items.BOW)));
            test.assertTrue(player.getMainHandItem().is(Items.ROTTEN_FLESH),"Creative slot packet cannot inject a bow");
            item=new ItemEntity(player.level(),player.getX(),player.getY(),player.getZ(),new ItemStack(Items.DIAMOND));
            player.level().addFreshEntity(item); item.setNoPickUpDelay(); item.playerTouch(player);
            test.assertTrue(!item.isRemoved() && item.getItem().is(Items.DIAMOND),"Monster cannot pick up dropped items");
            player.getInventory().setSelectedSlot(0);
            var chest=player.blockPosition().offset(2,0,0);
            player.level().setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
            UseBlockCallback.EVENT.invoker().interact(player,player.level(),InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chest),Direction.UP,chest,false));
            test.assertTrue(player.containerMenu==player.inventoryMenu,"Container interaction does not open a chest");
            test.assertTrue(!MonsterRoles.burst(player),"Zombie cannot use skeleton arrows");
            test.assertTrue(player.level().getEntitiesOfClass(AbstractArrow.class,player.getBoundingBox().inflate(10)).isEmpty(),"Zombie has no ordinary bow path");
        } finally { if(item!=null)item.discard(); remove(player); }
        test.succeed();
    }

    @GameTest(maxTicks=300)
    public void nativeMeleeHasOneTargetAndUsesActualAutomaticWeapon(GameTestHelper test) {
        var zombie=actor(test); var human=actor(test);var expectedTarget=actor(test);
        human.teleportTo(zombie.getX(),zombie.getY(),zombie.getZ()+1.0);
        MonsterRoles.select(zombie,"zombie");
        drop(zombie,new ItemStack(Items.DIAMOND_SWORD)).playerTouch(zombie);
        zombie.getInventory().setSelectedSlot(1);
        expectedTarget.teleportTo(zombie.getX()-1,zombie.getY(),zombie.getZ());
        human.setGameMode(GameType.SURVIVAL);
        test.runAfterDelay(80,()->{
            try {
                float before=human.getHealth();
                var model=MonsterRoles.nativeModel(zombie);
                model.doHurtTarget(zombie.level(),expectedTarget);
                System.out.println("Monster melee diagnostic legal="+MonsterRoles.legalTarget(zombie.getUUID(),zombie.level(),human)
                    +" owner="+zombie.position()+" target="+human.position()+" reach="+model.isWithinMeleeAttackRange(human)
                    +" los="+zombie.hasLineOfSight(human)+" loaded="+human.connection.hasClientLoaded()
                    +" invul="+human.isInvulnerableTo(human.level(),model.damageSources().mobAttack(model)));
                test.assertTrue(MonsterRoles.attack(zombie,human),"Native zombie melee damages a human");
                test.assertTrue(human.getHealth()<before,"Exactly the native attack is applied");
                test.assertValueEqual(human.getHealth(),expectedTarget.getHealth(),"Actual weapon matches native damage while skill control is selected");
                float after=human.getHealth();
                test.assertTrue(!MonsterRoles.attack(zombie,human) && human.getHealth()==after,"Native 20-tick melee interval prevents spam");
                var pig=EntityTypes.PIG.create(zombie.level(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                pig.snapTo(zombie.position().add(0,0,1)); zombie.level().addFreshEntity(pig);
                test.assertTrue(!MonsterRoles.attack(zombie,pig),"Monster cannot attack an animal"); pig.discard();
                MonsterRoles.select(zombie,"skeleton");
                test.assertTrue(!MonsterRoles.attack(zombie,human),"Fixed-bow skeleton cannot fall back to player melee");
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
    public void summonsAreTwoNativeBabiesAndCooldownSurvivesHumanSwitch(GameTestHelper test) {
        var owner=actor(test); var human=actor(test);
        MonsterRoles.select(owner,"zombie");
        var children=new java.util.ArrayList<Zombie>();
        test.runAfterDelay(10,()->{
        cleanArena(owner);
        try {
            test.assertTrue(MonsterRoles.summon(owner),"Both safe positions spawn successfully");
            for(var child:owner.level().getEntitiesOfClass(Zombie.class,owner.getBoundingBox().inflate(8))) {
                if(owner.getUUID().equals(MonsterRoles.summonOwner(child))) children.add(child);
            }
            test.assertValueEqual(children.size(),2,"Exactly two babies");
            for(var child:children) {
                test.assertTrue(child.isBaby() && !child.canPickUpLoot(),"Native baby, no equipment pickup");
                test.assertValueEqual(child.getMaxHealth(),20f,"Native zombie health, not old custom 12HP");
                child.setTarget(owner); test.assertTrue(child.getTarget()==null,"Cannot attack summoner");
                child.setTarget(human); test.assertTrue(child.getTarget()==human,"Can target another human");
            }
            test.assertTrue(!MonsterRoles.summon(owner),"Repeated skill does not spawn extra children");
            MonsterRoles.clear(owner); MonsterRoles.select(owner,"zombie");
            test.assertTrue(!MonsterRoles.summon(owner),"Human identity switch does not reset skill cooldown");
            for(var child:children) { child.setTarget(owner); test.assertTrue(child.getTarget()!=owner,"Owner exclusion remains in persistent tags"); }
        } finally { children.forEach(Entity::discard); remove(owner); remove(human); }
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
                    test.assertTrue(player.getInventory().getItem(8).getCount()==1,"Virtual ammo remains infinite");
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
            test.assertTrue(MonsterRoles.ignite(creeper),"Native creeper fuse starts");
            test.assertTrue(!MonsterRoles.ignite(creeper),"Repeated ignite cannot duplicate a blast");
            test.assertValueEqual(creeper.getAttributeValue(Attributes.MOVEMENT_SPEED),0d,"Native swell stops movement");
            test.startSequence().thenWaitUntil(()->test.assertTrue(!creeper.isAlive(),"Blast ends the prototype body"))
                .thenExecute(()->{
                    try {
                        test.assertTrue(human.getHealth()<20 || !human.isAlive(),"Legal human receives native blast damage");
                        test.assertValueEqual(pig.getHealth(),pigHealth,"Animal receives no blast damage");
                        test.assertTrue(Math.abs(pig.getDeltaMovement().x)<.0001 && Math.abs(pig.getDeltaMovement().z)<.0001,"Animal receives no illegal knockback");
                        test.assertTrue(creeper.level().getBlockState(floor).is(Blocks.SMOOTH_STONE),"Blocks are preserved");
                        var loot=creeper.level().getEntitiesOfClass(ItemEntity.class,creeper.getBoundingBox().inflate(5));
                        test.assertTrue(loot.isEmpty(),"Fixed role controls do not drop as loot");
                    } finally { pig.discard(); remove(creeper); remove(human); }
                }).thenSucceed();
        });
    }

    @GameTest(maxTicks=200)
    public void automaticEquipmentUsesNativeComparisonAndConservesWorldItems(GameTestHelper test) {
        var player=actor(test); var observer=actor(test);
        var items=new java.util.ArrayList<ItemEntity>();
        test.runAfterDelay(10,()->{
        cleanArena(player);
        try {
            MonsterRoles.select(player,"zombie");
            var sword=drop(player,new ItemStack(Items.DIAMOND_SWORD));items.add(sword);sword.playerTouch(player);
            test.assertTrue(sword.isRemoved(),"Accepted world weapon is consumed once");
            test.assertTrue(MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD),"Native mob equips weapon immediately");
            test.assertTrue(MonsterRoles.nativeModel(player).getAttributeValue(Attributes.ATTACK_DAMAGE)>3,"Native weapon modifiers apply to combat model");
            test.assertTrue(Math.abs(player.getAttributeValue(Attributes.ATTACK_SPEED)-1)<.00001,"Weapon modifiers cannot freeze melee indicator");
            var helmet=drop(player,new ItemStack(Items.IRON_HELMET));items.add(helmet);helmet.playerTouch(player);
            test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),"Auto armor is mirrored to physical protection");
            var worse=drop(player,new ItemStack(Items.LEATHER_HELMET));items.add(worse);worse.playerTouch(player);
            test.assertTrue(!worse.isRemoved() && player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),"Native comparison rejects worse armor");
            player.getInventory().setSelectedSlot(1);
            var packet=new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(player.getId(),List.of(
                com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND,player.getMainHandItem())));
            MonsterRoles.repairLoadout(player);
            test.assertTrue(Math.abs(player.getAttributeValue(Attributes.ATTACK_SPEED)-1)<.00001,"Skill control preserves melee indicator");
            var rewritten=(net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket)DisguiseProbe.rewrite(observer,packet);
            test.assertTrue(rewritten.getSlots().stream().anyMatch(pair->pair.getFirst()==EquipmentSlot.MAINHAND && pair.getSecond().is(Items.DIAMOND_SWORD)),"Observer sees actual weapon while owner selects skill control");
            for(var input:ContainerInput.values()) {
                player.connection.handleContainerClick(new ServerboundContainerClickPacket(0,player.inventoryMenu.getStateId(),(short)36,(byte)0,
                    input,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),HashedStack.EMPTY));
            }
            test.assertTrue(MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD),"Inventory packets cannot change actual gear");
            MonsterRoles.onDisconnect(player);MonsterRoles.onJoin(player);
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
    public void skeletonPrefersBowAndUsesActualEnchantedWeapon(GameTestHelper test) {
        var player=actor(test);var items=new java.util.ArrayList<ItemEntity>();
        MonsterRoles.select(player,"skeleton");
        test.runAfterDelay(10,()->{
        cleanArena(player);
        try {
            var sword=drop(player,new ItemStack(Items.NETHERITE_SWORD));items.add(sword);sword.playerTouch(player);
            test.assertTrue(!sword.isRemoved() && MonsterRoles.actualEquipment(player,EquipmentSlot.MAINHAND).is(Items.BOW),"Native bow preference prevents sword switching");
            var bow=new ItemStack(Items.BOW);
            bow.enchant(player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.POWER),3);
            var expectedBow=bow.copy();
            var enchanted=drop(player,bow);items.add(enchanted);enchanted.playerTouch(player);
            test.assertTrue(enchanted.isRemoved(),"Native comparison upgrades enchanted bow");
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
    public void nativeHelmetProtectsFromSunlightAndWaterSuppressesBurning(GameTestHelper test) {
        var player=actor(test);
        var clock=player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
            .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        long daytime=player.level().clockManager().getInstance(clock).totalTicks();
        var weather=player.level().getWeatherData();
        boolean raining=weather.isRaining(),thundering=weather.isThundering();
        int clearTime=weather.getClearWeatherTime(),rainTime=weather.getRainTime(),thunderTime=weather.getThunderTime();
        weather.setClearWeatherTime(6000);weather.setRaining(false);weather.setThundering(false);
        MonsterRoles.select(player,"zombie");
        player.level().setBlockAndUpdate(player.blockPosition().above(3),Blocks.AIR.defaultBlockState());
        player.level().clockManager().setTotalTicks(clock,6000);
        // Allow sky lighting and clock-dependent environment caches to update.
        test.runAfterDelay(10,()->{
          cleanArena(player);
          ItemEntity helmet=null;
          try {
            helmet=drop(player,new ItemStack(Items.IRON_HELMET));helmet.playerTouch(player);
            player.setRemainingFireTicks(0);
            for(int i=0;i<500;i++)MonsterRoles.updateEnvironment(player);
            test.assertTrue(player.getRemainingFireTicks()<=0,"Helmet suppresses native sun ignition");
            test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).getDamageValue()>0,"Native exposure wears helmet");
            var equipped=MonsterRoles.nativeModel(player).getItemBySlot(EquipmentSlot.HEAD);
            equipped.setDamageValue(equipped.getMaxDamage()-1);
            for(int i=0;i<1500;i++)MonsterRoles.updateEnvironment(player);
            test.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getRemainingFireTicks()>0,"Broken helmet restores sun vulnerability");
            player.level().setBlockAndUpdate(player.blockPosition(),Blocks.WATER.defaultBlockState());
            player.level().setBlockAndUpdate(player.blockPosition().above(),Blocks.WATER.defaultBlockState());
            player.setRemainingFireTicks(0);
            for(int i=0;i<200;i++)MonsterRoles.updateEnvironment(player);
            test.assertTrue(player.getRemainingFireTicks()<=0,"Real water state suppresses sun ignition");
          } finally {
            player.level().clockManager().setTotalTicks(clock,daytime);
            weather.setClearWeatherTime(clearTime);weather.setRainTime(rainTime);weather.setThunderTime(thunderTime);
            weather.setRaining(raining);weather.setThundering(thundering);
            player.level().setBlockAndUpdate(player.blockPosition(),Blocks.AIR.defaultBlockState());
            player.level().setBlockAndUpdate(player.blockPosition().above(),Blocks.AIR.defaultBlockState());
            if(helmet!=null)helmet.discard();remove(player);
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

    private static ServerPlayer actor(GameTestHelper test) {
        int center=1000+ARENA.getAndIncrement()*24;
        var server=test.getLevel().getServer();
        var profile=new GameProfile(java.util.UUID.randomUUID(),"HRRole"+center);
        var player=new ServerPlayer(server,test.getLevel(),profile,ClientInformation.createDefault());
        var connection=new SinkConnection();
        CHANNELS.put(player.getUUID(),new EmbeddedChannel(connection));
        dev.hardrush.p1.player.P1PlayerProfiles.runUnmanagedFixture(() ->
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
    private static void remove(ServerPlayer player) {
        MonsterRoles.clear(player); DisguiseProbe.clear(player);
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
