package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.monster.mixin.MonsterMobAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The zombified piglin's own ways (docs/plan/怪物环境变种.md §4): fireproof body, golden sword, neutral kin; without a client. */
public final class MonsterPiglinGameTest {
    /** A freshly placed player is natively invulnerable for a while. */
    private static final int SPAWN_INVULNERABILITY = 80;

    @GameTest(maxTicks=400)
    public void aZombieLandingInTheNetherTakesUpAnUnbreakableSwordAndFireImmunity(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        TestTeardown.atEnd(test, () -> MonsterRoleGameTest.remove(p));
        var overworld = p.level(); var position = p.position();
        test.startSequence().thenIdle(SPAWN_INVULNERABILITY).thenExecute(() -> {
            MonsterRoles.select(p, "zombie");
            MonsterRoleGameTest.withEggs(p);
            p.getInventory().setSelectedSlot(2);
            p.teleportTo(overworld.getServer().getLevel(Level.NETHER), .5, 150, .5, Set.of(), 0, 0, true);
            MonsterRoles.tick(overworld.getServer());
            test.assertTrue(MonsterRoles.settle(p, false), "A zombie landing in the nether changes form");
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.ZOMBIFIED_PIGLIN, "into a zombified piglin");
            var sword = p.getMainHandItem();
            test.assertTrue(sword.is(Items.GOLDEN_SWORD) && MonsterItems.matches(p, sword, MonsterRoles.PIGLIN_SWORD),
                    "The landing puts the bound golden sword into its hand");
            test.assertTrue(sword.has(DataComponents.UNBREAKABLE) && !sword.isDamageableItem(), "The sword never wears out");
            test.assertValueEqual(countPurpose(p, MonsterCompanions.ADULT_EGG), 1, "What it held moves aside, kept");
            test.assertTrue(MonsterRoles.heldHint(p, sword).isEmpty(), "The sword explains itself in its description only");
            assertFireproof(test, p, "Landed in the nether");
            test.assertTrue(!MonsterRoles.settle(p, false), "Landing again changes nothing");
            test.assertValueEqual(countPurpose(p, MonsterRoles.PIGLIN_SWORD), 1, "and gives no second sword");
            // A sword left in the crafting grid of an open inventory is still the body's one sword.
            var inGrid = p.getMainHandItem().copy();
            p.getInventory().setItem(p.getInventory().getSelectedSlot(), ItemStack.EMPTY);
            p.inventoryMenu.slots.get(1).set(inGrid);
            test.assertTrue(!MonsterRoles.settle(p, false), "Landing with the sword in the grid changes nothing");
            test.assertValueEqual(countPurpose(p, MonsterRoles.PIGLIN_SWORD), 1, "and gives no second sword beside the grid's");
            p.inventoryMenu.slots.get(1).set(ItemStack.EMPTY);
            p.getInventory().setItem(p.getInventory().getSelectedSlot(), inGrid);
            p.teleportTo(overworld, position.x, position.y, position.z, Set.of(), 0, 0, true);
            MonsterRoles.tick(overworld.getServer());
            assertFireproof(test, p, "Rebinding in another dimension");

            MonsterRoles.convert(p, MonsterForm.DROWNED);
            test.assertFalse(p.fireImmune(), "A drowned burns again");
            test.assertTrue(p.getEffect(MobEffects.NIGHT_VISION) != null && MonsterRoles.intrinsic(p.getEffect(MobEffects.NIGHT_VISION)),
                    "It still sees in the dark");
            test.assertValueEqual(countPurpose(p, MonsterRoles.PIGLIN_SWORD), 0, "The piglin's sword went with the piglin");
            test.assertValueEqual(countPurpose(p, MonsterRoles.SEA_TRIDENT), 1, "The drowned takes its trident");
        }).thenSucceed();
    }

    /** In one level throughout: a player changing dimension is natively invulnerable until its client catches up. */
    @GameTest(maxTicks=300)
    public void fireAndLavaDoAPiglinBodyNoHarm(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        TestTeardown.atEnd(test, () -> MonsterRoleGameTest.remove(p));
        var level = p.level();
        test.startSequence().thenIdle(SPAWN_INVULNERABILITY).thenExecute(() -> {
            MonsterRoles.select(p, "zombie");
            test.assertTrue(p.hurtServer(level, p.damageSources().inFire(), 1), "Fire hurts a zombie");
            p.clearFire();
        }).thenIdle(25).thenExecute(() -> {
            MonsterRoles.convert(p, MonsterForm.ZOMBIFIED_PIGLIN);
            assertFireproof(test, p, "Converted");
            float health = p.getHealth();
            for (var source : List.of(p.damageSources().inFire(), p.damageSources().onFire(), p.damageSources().lava(), p.damageSources().hotFloor()))
                test.assertFalse(p.hurtServer(level, source, 4), "Fire and lava do no harm: " + source.getMsgId());
            test.assertValueEqual(p.getHealth(), health, "Not a heart lost to fire or lava");
            p.lavaIgnite();
            test.assertTrue(p.getRemainingFireTicks() <= 0, "Lava lights no fire on it");
            // A fire block or burning arrow sets the fire unconditionally; the native tick puts it out at once.
            p.igniteForSeconds(8);
            test.assertFalse(p.isOnFire(), "It never reads as burning");
        }).thenIdle(3).thenExecute(() -> {
            test.assertTrue(p.getRemainingFireTicks() <= 0, "The fire is out within a tick, so no one sees it burn");
            test.assertFalse(p.hasEffect(MobEffects.FIRE_RESISTANCE), "Still no potion effect behind it");
            MonsterRoles.convert(p, MonsterForm.DROWNED);
            test.assertFalse(p.fireImmune(), "Out of the piglin body it is no longer immune");
            test.assertTrue(p.hurtServer(level, p.damageSources().inFire(), 1), "Out of the piglin body fire hurts again");
            p.igniteForSeconds(8);
            test.assertTrue(p.isOnFire(), "and it can burn again");
            p.clearFire();
        }).thenSucceed();
    }

    @GameTest(maxTicks=200)
    public void aPiglinBodyDrinksFireResistanceAsItsOwnPotionAndParksIt(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoles.convert(p, MonsterForm.ZOMBIFIED_PIGLIN);
            assertFireproof(test, p, "Converted");
            test.assertTrue(countPurpose(p, MonsterRoles.PIGLIN_SWORD) == 1, "A converted zombified piglin carries its sword");
            MonsterPotions.apply(p, MonsterRole.ZOMBIE, new dev.hardrush.core.round.PotionBox.Dose(
                    dev.hardrush.core.round.PotionBrew.FIRE_RESISTANCE, dev.hardrush.core.round.PotionBrew.Grade.COMMON, 0, 300));
            var drunk = p.getEffect(MobEffects.FIRE_RESISTANCE);
            test.assertTrue(drunk != null && !drunk.isInfiniteDuration() && !MonsterRoles.intrinsic(drunk),
                    "A witch's fire resistance is not refused: it is a timed potion of its own");
            assertFireproof(test, p, "After drinking fire resistance");
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 1200));

            var body = MonsterParking.decode(p, MonsterParking.capture(p), MonsterRole.ZOMBIE).orElseThrow();
            test.assertValueEqual(body.form(), MonsterForm.ZOMBIFIED_PIGLIN, "The piglin parks as itself");
            test.assertFalse(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.NIGHT_VISION)),
                    "The body's own night vision stays out of the parking; the next bind gives it afresh");
            test.assertTrue(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.FIRE_RESISTANCE)), "The drunk fire resistance parks");
            test.assertTrue(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.SPEED)), "Timed effects park");
            var parkedSword = body.items().values().stream().filter(stack -> MonsterItems.purpose(stack).equals(MonsterRoles.PIGLIN_SWORD)).toList();
            test.assertValueEqual(parkedSword.size(), 1, "The sword parks with the body");
            test.assertTrue(parkedSword.getFirst().has(DataComponents.UNBREAKABLE), "still unbreakable");

            MonsterRoles.clear(p);
            test.assertFalse(p.fireImmune(), "Leaving the body takes the immunity away");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=300)
    public void theGoldenSwordHitsHarderThanAnEmptyHand(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        var level = p.level();
        var cows = new ArrayList<Cow>();
        TestTeardown.atEnd(test, () -> { cows.forEach(Entity::discard); MonsterRoleGameTest.remove(p); });
        float[] lost = new float[2];
        test.startSequence().thenIdle(SPAWN_INVULNERABILITY).thenExecute(() -> {
            MonsterRoles.select(p, "zombie");
            MonsterRoles.convert(p, MonsterForm.ZOMBIFIED_PIGLIN);
            for (int side : new int[] {1, -1}) {
                var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                cow.snapTo(p.getX() + side * 1.2, p.getY(), p.getZ(), 0, 0);
                cow.setNoAi(true);
                level.addFreshEntity(cow);
                cows.add(cow);
            }
            hold(p, MonsterRoles.PIGLIN_SWORD);
            float before = cows.get(0).getHealth();
            test.assertTrue(MonsterRoles.attack(p, cows.get(0)), "The sword hit lands");
            lost[0] = before - cows.get(0).getHealth();
            test.assertTrue(MonsterItems.matches(p, p.getMainHandItem(), MonsterRoles.PIGLIN_SWORD)
                    && p.getMainHandItem().getDamageValue() == 0, "The native hit leaves the sword whole");
        }).thenIdle(30).thenExecute(() -> {
            p.getInventory().setItem(4, ItemStack.EMPTY);
            p.getInventory().setSelectedSlot(4);
            MonsterRoles.repairLoadout(p);
            float before = cows.get(1).getHealth();
            test.assertTrue(MonsterRoles.attack(p, cows.get(1)), "The empty-handed hit lands");
            lost[1] = before - cows.get(1).getHealth();
            // The body hits with the zombie's configured base (3 by default), not the native zombified piglin's 5.
            double base = MonsterRoles.nativeModel(p).getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue();
            test.assertValueEqual(base, dev.hardrush.core.config.RulesConfig.defaults().combatValues().get("zombie.baseMeleeDamage"),
                    "The model's base attack is the zombie rule's");
            test.assertTrue(Math.abs(lost[1] - base) < 1e-4, "The empty hand deals the base: " + lost[1]);
            double sword = new ItemStack(Items.GOLDEN_SWORD).getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .compute(Attributes.ATTACK_DAMAGE, 0, EquipmentSlot.MAINHAND);
            test.assertTrue(sword > 0 && Math.abs(lost[0] - lost[1] - sword) < 1e-4,
                    "The golden sword adds exactly its own attack bonus " + sword + ": " + lost[0] + " vs " + lost[1]);
        }).thenSucceed();
    }

    @GameTest(maxTicks=300)
    public void aSlainPiglinsSwordDropsAsAPlainGoldenSword(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test); var human = MonsterRoleGameTest.actor(test);
        TestTeardown.atEnd(test, () -> {
            for (var item : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6))) item.discard();
            MonsterRoleGameTest.remove(p); MonsterRoleGameTest.remove(human);
        });
        test.startSequence().thenIdle(SPAWN_INVULNERABILITY).thenExecute(() -> {
            MonsterRoles.select(p, "zombie");
            MonsterRoles.convert(p, MonsterForm.ZOMBIFIED_PIGLIN);
            hold(p, MonsterRoles.PIGLIN_SWORD);
            human.setGameMode(GameType.SURVIVAL);
            human.teleportTo(p.getX(), p.getY(), p.getZ() + 2);
            DamageSource hit = human.damageSources().playerAttack(human);
            test.assertTrue(p.hurtServer(p.level(), hit, 1), "The human's hit lands");
            // Every drop roll succeeds.
            MonsterRoles.dropWornEquipment(p, hit, new LegacyRandomSource(1) {
                @Override public float nextFloat() { return 0; }
            });
            var dropped = p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4)).stream()
                    .map(ItemEntity::getItem).filter(stack -> stack.is(Items.GOLDEN_SWORD)).toList();
            test.assertValueEqual(dropped.size(), 1, "The worn sword drops like a native piglin's");
            var sword = dropped.getFirst();
            test.assertTrue(!MonsterItems.bound(sword) && !sword.has(DataComponents.UNBREAKABLE) && !sword.has(DataComponents.LORE),
                    "Dropped, it is a plain golden sword: unbound, breakable, no sortie description");
            test.assertTrue(sword.isDamageableItem() && sword.getDamageValue() > 0, "with native wear");
        }).thenSucceed();
    }

    /**
     * Companions keep the piglin's temper, and a human striking the piglin player provokes every zombified piglin
     * around, wild ones included. One sequence: concurrent piglin fixtures would hear each other's calls.
     */
    @GameTest(maxTicks=600)
    public void zombifiedPiglinsAreNeutralUntilAHumanProvokesOne(GameTestHelper test) {
        var owner = MonsterRoleGameTest.actor(test);
        var human = MonsterRoleGameTest.actor(test);
        var other = MonsterRoleGameTest.actor(test);
        var monster = MonsterRoleGameTest.actor(test);
        var level = owner.level();
        var spawned = new ArrayList<Entity>();
        BlockPos[] wall = new BlockPos[1];
        TestTeardown.atEnd(test, () -> {
            spawned.forEach(Entity::discard);
            if (wall[0] != null) for (int y = 0; y < 3; y++) for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(wall[0].offset(0, y, dz), Blocks.AIR.defaultBlockState());
            for (var player : List.of(owner, human, other, monster)) MonsterRoleGameTest.remove(player);
        });
        ZombifiedPiglin[] piglin = new ZombifiedPiglin[1], wild = new ZombifiedPiglin[1];
        test.startSequence().thenIdle(SPAWN_INVULNERABILITY).thenExecute(() -> {
            MonsterRoles.select(owner, "zombie");
            MonsterRoles.convert(owner, MonsterForm.ZOMBIFIED_PIGLIN);
            MonsterRoles.select(monster, "skeleton");
            human.setGameMode(GameType.SURVIVAL);
            // Kept alive through the piglins' blows; damage is not what this test is about.
            human.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 2000, 4));
            human.teleportTo(owner.getX(), owner.getY(), owner.getZ() + 2);
            piglin[0] = (ZombifiedPiglin) hatch(test, owner, MonsterForm.ZOMBIFIED_PIGLIN);
            var zombie = hatch(test, owner, MonsterForm.ZOMBIE);
            wild[0] = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.COMMAND);
            wild[0].snapTo(owner.getX() - 3, owner.getY(), owner.getZ(), 0, 0);
            level.addFreshEntity(wild[0]);
            for (var mob : List.of(piglin[0], zombie, wild[0])) {
                spawned.add(mob);
                ((MonsterMobAccessor) mob).hardrush$goalSelector().removeAllGoals(goal -> goal instanceof WaterAvoidingRandomStrollGoal);
            }
            test.assertTrue(((MonsterMobAccessor) piglin[0]).hardrush$targetSelector().getAvailableGoals().stream()
                    .noneMatch(goal -> goal.getGoal().getClass().getSimpleName().equals("CompanionCommandGoal")), "A piglin companion takes no orders");
            test.assertTrue(((MonsterMobAccessor) piglin[0]).hardrush$goalSelector().getAvailableGoals().stream()
                    .anyMatch(goal -> goal.getGoal().getClass().getSimpleName().equals("CompanionFollowGoal")), "but follows its owner");

            MonsterCompanions.onOwnerAttack(owner, human);
            test.assertTrue(piglin[0].getTarget() == null, "The owner striking a human sets no piglin companion on them");
            var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            cow.snapTo(owner.getX() + 2, owner.getY(), owner.getZ(), 0, 0);
            level.addFreshEntity(cow); spawned.add(cow);
            test.assertTrue(cow.hurtServer(level, owner.damageSources().mobAttack(MonsterRoles.nativeModel(owner)), 1), "The owner deals damage");
            test.assertTrue(zombie.getTarget() == cow, "A zombie companion takes the order");
            test.assertTrue(piglin[0].getTarget() == null && wild[0].getTarget() == null, "No zombified piglin takes it");
            zombie.discard(); cow.discard();

            test.assertTrue(owner.hurtServer(level, human.damageSources().playerAttack(human), 1), "The human strikes the piglin player");
            test.assertTrue(piglin[0].getTarget() == human, "Its companion turns on the human");
            test.assertTrue(wild[0].getTarget() == human, "So does a wild zombified piglin nearby");
        }).thenIdle(40).thenExecute(() -> {
            test.assertTrue(wild[0].getTarget() == human && wild[0].isAngry(), "The wild piglin stays on the human, angry the native way");
            test.assertTrue(piglin[0].getTarget() == human, "So does the companion");
            test.assertTrue(MonsterCompanions.owner(wild[0]) == null, "A provoked wild piglin stays wild");
            calm(piglin[0], wild[0]);
            test.assertTrue(owner.hurtServer(level, human.damageSources().playerAttack(human), 1), "The human strikes again");
            test.assertTrue(piglin[0].getTarget() == null && wild[0].getTarget() == null, "Within four seconds the piglin player calls no one again");
        }).thenIdle(MonsterPiglinAnger.ALERT_INTERVAL_TICKS - 30).thenExecute(() -> {
            test.assertTrue(owner.hurtServer(level, human.damageSources().playerAttack(human), 1), "The human strikes once more");
            test.assertTrue(piglin[0].getTarget() == human && wild[0].getTarget() == human, "After the rest it calls them again; companion="
                    + piglin[0].getTarget() + " alive=" + piglin[0].isAlive() + " wild=" + wild[0].getTarget() + " alive=" + wild[0].isAlive()
                    + " sight=" + owner.hasLineOfSight(human) + " legal=" + MonsterRoles.legalTarget(owner.getUUID(), (net.minecraft.server.level.ServerLevel) level, human)
                    + " form=" + MonsterRoles.currentForm(owner) + " owner=" + owner.position() + " human=" + human.position() + " wildAt=" + wild[0].position()
                    + " tick=" + level.getServer().getTickCount());
            calm(piglin[0], wild[0]);
            test.assertTrue(piglin[0].hurtServer(level, human.damageSources().playerAttack(human), 1), "The human strikes the companion");
        }).thenIdle(10).thenExecute(() -> {
            test.assertTrue(piglin[0].getTarget() == human, "The struck companion turns on the human natively");
            test.assertTrue(wild[0].getTarget() == human, "and its native alert brings the wild piglin along");
            calm(piglin[0], wild[0]);

            // A second piglin player with a fresh call: blocked sight and monsters' hits call no one.
            other.teleportTo(owner.getX(), owner.getY(), owner.getZ() - 4);
            MonsterRoles.select(other, "zombie");
            MonsterRoles.convert(other, MonsterForm.ZOMBIFIED_PIGLIN);
            human.teleportTo(other.getX() + 4, other.getY(), other.getZ());
            wall[0] = other.blockPosition().east(2);
            for (int y = 0; y < 3; y++) for (int dz = -2; dz <= 2; dz++) level.setBlockAndUpdate(wall[0].offset(0, y, dz), Blocks.STONE.defaultBlockState());
            test.assertFalse(other.hasLineOfSight(human), "The wall hides the human");
            test.assertTrue(other.hurtServer(level, human.damageSources().playerAttack(human), 1), "The hidden human's hit lands");
            test.assertTrue(piglin[0].getTarget() == null && wild[0].getTarget() == null, "A piglin player that cannot see its attacker calls no one");
            test.assertFalse(other.hurtServer(level, monster.damageSources().playerAttack(monster), 1), "Monsters do not hurt each other");
            MonsterPiglinAnger.provoked(other, monster.damageSources().playerAttack(monster));
            test.assertTrue(piglin[0].getTarget() == null && wild[0].getTarget() == null, "A fellow monster's hit calls no one");
            for (int y = 0; y < 3; y++) for (int dz = -2; dz <= 2; dz++) level.setBlockAndUpdate(wall[0].offset(0, y, dz), Blocks.AIR.defaultBlockState());
        }).thenIdle(15).thenExecute(() -> {
            test.assertTrue(other.hasLineOfSight(human), "In sight again");
            // The companion the human struck before may have taken it back up natively; the wild one is still calm.
            test.assertTrue(wild[0].getTarget() == null, "Nothing has called the wild piglin yet");
            // Thorns names the wearer, though it is the piglin player that struck: it calls no one.
            MonsterPiglinAnger.provoked(other, human.damageSources().thorns(human));
            test.assertTrue(wild[0].getTarget() == null, "The human's thorns call no one");
            human.getAbilities().invulnerable = true;
            MonsterPiglinAnger.provoked(other, human.damageSources().playerAttack(human));
            test.assertTrue(wild[0].getTarget() == null, "A human no monster may target is set on by no one");
            human.getAbilities().invulnerable = false;
            var arrow = new Arrow(level, human, new ItemStack(Items.ARROW), null);
            spawned.add(arrow);
            MonsterPiglinAnger.provoked(other, human.damageSources().arrow(arrow, human));
            test.assertTrue(wild[0].getTarget() == human, "The human's arrow calls them; none of the refusals spent the call");

            MonsterRoles.clear(owner);
        }).thenIdle(2).thenExecute(() -> {
            test.assertTrue(!piglin[0].isAlive(), "The sortie's end dismisses the piglin companion");
            test.assertTrue(wild[0].isAlive() && !wild[0].isRemoved(), "The wild piglin is left in the world");
        }).thenSucceed();
    }

    /** Back to neutral: no target, no anger. */
    private static void calm(ZombifiedPiglin... piglins) {
        for (var piglin : piglins) piglin.stopBeingAngry();
    }

    private static Mob hatch(GameTestHelper test, ServerPlayer owner, MonsterForm form) {
        var floor = owner.blockPosition().east(2).below();
        owner.setItemInHand(InteractionHand.MAIN_HAND, MonsterCompanions.egg(owner, false, form));
        test.assertTrue(MonsterCompanions.useOn(owner, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0), Direction.UP, floor, false)), "The egg hatches: " + form);
        return owner.level().getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(8)).stream()
                .filter(zombie -> zombie.getType() == form.type && owner.getUUID().equals(MonsterCompanions.owner(zombie)))
                .reduce((first, second) -> second).orElseThrow();
    }

    /** Moves the bound {@code purpose} item into the eighth hotbar slot and holds it. */
    private static void hold(ServerPlayer p, String purpose) {
        var inventory = p.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (MonsterItems.matches(p, inventory.getItem(i), purpose)) {
            var stack = inventory.getItem(i);
            inventory.setItem(i, ItemStack.EMPTY);
            inventory.setItem(8, stack);
            break;
        }
        inventory.setSelectedSlot(8);
        MonsterRoles.repairLoadout(p);
    }

    /** Natively fire immune, as the species is; no potion effect of its own stands for it. */
    private static void assertFireproof(GameTestHelper test, ServerPlayer p, String when) {
        test.assertTrue(p.fireImmune(), when + ": natively fire immune");
        test.assertFalse(p.isOnFire(), when + ": never burning");
        var own = p.getEffect(MobEffects.FIRE_RESISTANCE);
        test.assertTrue(own == null || !MonsterRoles.intrinsic(own), when + ": no fire resistance of the body's own");
    }

    private static int countPurpose(ServerPlayer p, String purpose) {
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (MonsterItems.matches(p, p.getInventory().getItem(i), purpose)) count += p.getInventory().getItem(i).getCount();
        if (MonsterItems.matches(p, p.containerMenu.getCarried(), purpose)) count += p.containerMenu.getCarried().getCount();
        for (int i = 1; i <= 4; i++) if (MonsterItems.matches(p, p.inventoryMenu.slots.get(i).getItem(), purpose)) count += p.inventoryMenu.slots.get(i).getItem().getCount();
        return count;
    }
}
