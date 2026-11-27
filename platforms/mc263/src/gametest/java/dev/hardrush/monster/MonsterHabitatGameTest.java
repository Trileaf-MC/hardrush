package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.gametest.MonsterRoleGameTest;
import dev.hardrush.monster.mixin.MonsterSkeletonAccessor;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;

/** The landing variants of docs/plan/怪物环境变种.md: husk, zombified piglin, bogged and parched bodies; in-process, without a client. */
public final class MonsterHabitatGameTest {
    @GameTest(maxTicks=200)
    public void everyNewZombieVariantTakesItsEggsAndParksAsItself(GameTestHelper test) {
        for (var form : List.of(MonsterForm.HUSK, MonsterForm.ZOMBIFIED_PIGLIN)) {
            var p = MonsterRoleGameTest.actor(test);
            try {
                MonsterRoles.select(p, "zombie");
                float health = p.getMaxHealth();
                MonsterRoleGameTest.withEggs(p);
                MonsterRoles.convert(p, form);
                test.assertValueEqual(MonsterRoles.currentForm(p), form, "The body turns: " + form);
                test.assertValueEqual(MonsterRoles.nativeModel(p).getType(), form.type, "The native model is the variant: " + form);
                test.assertValueEqual(p.getMaxHealth(), health, "A zombie variant keeps the zombie's health: " + form);
                test.assertValueEqual(eggs(p, form.egg), 2, "Both eggs hatch the variant: " + form);
                assertParks(test, p, form, MonsterRole.ZOMBIE);
            } finally { MonsterRoleGameTest.remove(p); }
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void boggedAndParchedNameTheirBowAndPayWithAFifthOfTheirHealth(GameTestHelper test) {
        for (var form : List.of(MonsterForm.BOGGED, MonsterForm.PARCHED)) {
            var p = MonsterRoleGameTest.actor(test);
            try {
                MonsterRoles.select(p, "skeleton");
                float health = p.getMaxHealth();
                MonsterRoles.convert(p, form);
                test.assertValueEqual(MonsterRoles.currentForm(p), form, "The body turns: " + form);
                test.assertValueEqual(MonsterRoles.nativeModel(p).getType(), form.type, "The native model is the variant: " + form);
                test.assertTrue(Math.abs(p.getMaxHealth() - health * .8f) < 1e-4, "Four fifths of the skeleton's health: " + form);
                test.assertTrue(p.getHealth() <= p.getMaxHealth(), "Health never exceeds the new maximum: " + form);
                test.assertValueEqual(MonsterBowDisplay.bowName(form), form == MonsterForm.BOGGED ? "沼骸之弓" : "焦骸之弓", "The bow is named after it");
                assertParks(test, p, form, MonsterRole.SKELETON);
            } finally { MonsterRoleGameTest.remove(p); }
        }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void aHuskSoaksBackIntoAZombieAndOnIntoADrowned(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "zombie");
            MonsterRoleGameTest.withEggs(p);
            MonsterRoles.convert(p, MonsterForm.HUSK);
            test.assertValueEqual(eggs(p, Items.HUSK_SPAWN_EGG), 2, "Husk eggs");
            test.assertValueEqual(MonsterForm.HUSK.conversion().into(), MonsterForm.ZOMBIE, "Water turns a husk back into a zombie");
            MonsterRoles.convert(p, MonsterForm.HUSK.conversion().into());
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.ZOMBIE, "First a zombie");
            test.assertValueEqual(eggs(p, Items.ZOMBIE_SPAWN_EGG), 2, "Zombie eggs again");
            MonsterRoles.convert(p, MonsterForm.ZOMBIE.conversion().into());
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "Then a drowned");
            test.assertValueEqual(eggs(p, Items.DROWNED_SPAWN_EGG), 2, "Drowned eggs");
            test.assertTrue(MonsterForm.ZOMBIFIED_PIGLIN.conversion() == null, "A zombified piglin never soaks into anything");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest(maxTicks=300)
    public void anEmptyHandedHuskStarvesWhatItHits(GameTestHelper test) {
        var husk = MonsterRoleGameTest.actor(test); var human = MonsterRoleGameTest.actor(test);
        human.teleportTo(husk.getX(), husk.getY(), husk.getZ() + 1.0);
        MonsterRoles.select(husk, "zombie");
        MonsterRoles.convert(husk, MonsterForm.HUSK);
        MonsterRoleGameTest.emptyHanded(husk);
        human.setGameMode(GameType.SURVIVAL);
        // A freshly placed player is natively invulnerable for a while.
        test.runAfterDelay(80, () -> {
            try {
                float before = human.getHealth();
                test.assertTrue(MonsterRoles.attack(husk, human), "The husk's native melee lands");
                test.assertTrue(human.getHealth() < before, "It hurts");
                // Husk.doHurtTarget: 140 ticks of hunger per whole point of regional difficulty.
                var level = husk.level();
                int ticks = 140 * (int) level.getCurrentDifficultyAt(husk.blockPosition()).getEffectiveDifficulty();
                var hunger = human.getEffect(MobEffects.HUNGER);
                if (ticks > 0) test.assertTrue(hunger != null && hunger.getDuration() <= ticks && hunger.getDuration() > ticks - 5,
                        "Hunger for the native duration: " + ticks + ", got " + hunger);
                else test.assertTrue(hunger == null, "No regional difficulty, no hunger (difficulty " + level.getDifficulty() + ")");
            } finally { MonsterRoleGameTest.remove(husk); MonsterRoleGameTest.remove(human); }
            test.succeed();
        });
    }

    @GameTest(maxTicks=300)
    public void aStrayArrowSlowsForTenSeconds(GameTestHelper test) {
        shootOnce(test, MonsterForm.STRAY, p -> {}, (p, arrow) -> assertOwnEffect(test, arrow, MonsterForm.STRAY, 200), null);
    }

    @GameTest(maxTicks=300)
    public void aParchedArrowWeakensForTenSecondsAndRestsLonger(GameTestHelper test) {
        shootOnce(test, MonsterForm.PARCHED, p -> {}, (p, arrow) -> assertOwnEffect(test, arrow, MonsterForm.PARCHED, 200), 50);
    }

    @GameTest(maxTicks=300)
    public void aBoggedArrowPoisonsForVanillasFiveSecondsAndRestsLonger(GameTestHelper test) {
        shootOnce(test, MonsterForm.BOGGED, p -> {}, (p, arrow) -> assertOwnEffect(test, arrow, MonsterForm.BOGGED, 100), 50);
    }

    @GameTest(maxTicks=300)
    public void aStrayKeepsBoughtSlownessArrowsAndShootsTheOthers(GameTestHelper test) {
        shootOnce(test, MonsterForm.STRAY, MonsterHabitatGameTest::buyArrows, (p, arrow) -> {
            test.assertValueEqual(count(p, Potions.SLOWNESS), 3, "The stray's own arrows slow already; the bought slowness arrows stay");
            test.assertValueEqual(count(p, Potions.POISON), 2, "A bought poison arrow is spent");
            var contents = arrow.getPickupItemStackOrigin().get(DataComponents.POTION_CONTENTS);
            test.assertTrue(contents.potion().isPresent() && contents.potion().get().is(Potions.POISON), "The bought potion is the arrow's own");
            assertOwnEffect(test, arrow, MonsterForm.STRAY, 200);
            setCount(p, Potions.POISON, 0);
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.ARROW), "Only slowness arrows left: the stray shoots its plain arrow");
        }, null);
    }

    @GameTest(maxTicks=200)
    public void eachBodyPassesOverOnlyTheBoughtArrowsItsOwnAlreadyMatch(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(p, "skeleton");
            buyArrows(p);
            test.assertTrue(isTipped(MonsterRoles.selectedArrow(p), Potions.SLOWNESS), "A skeleton shoots the first bought arrow");
            MonsterRoles.convert(p, MonsterForm.BOGGED);
            test.assertTrue(isTipped(MonsterRoles.selectedArrow(p), Potions.SLOWNESS), "A bogged shoots slowness arrows");
            setCount(p, Potions.SLOWNESS, 0);
            test.assertTrue(MonsterRoles.selectedArrow(p).is(Items.ARROW), "A bogged passes over poison arrows");
            test.assertValueEqual(count(p, Potions.POISON), 3, "They stay in the backpack");
        } finally { MonsterRoleGameTest.remove(p); }
        var q = MonsterRoleGameTest.actor(test);
        try {
            MonsterRoles.select(q, "skeleton");
            MonsterRoles.convert(q, MonsterForm.PARCHED);
            buyArrows(q);
            test.assertTrue(isTipped(MonsterRoles.selectedArrow(q), Potions.SLOWNESS), "A parched's weakness matches no bought arrow");
        } finally { MonsterRoleGameTest.remove(q); }
        test.succeed();
    }

    @GameTest(maxTicks=200)
    public void theLandingSiteReadsFromTheWorldAndSettlesABody(GameTestHelper test) {
        var p = MonsterRoleGameTest.actor(test);
        try {
            var site = MonsterHabitat.at(test.getLevel(), p.blockPosition(), false);
            test.assertTrue(!site.nether(), "The test level is no nether");
            // Its biome and sky depend on the test world; what it gives must agree with the pure table.
            test.assertValueEqual(MonsterHabitat.settle(MonsterForm.CREEPER, site), MonsterForm.CREEPER, "A creeper never changes");
            MonsterRoles.select(p, "zombie");
            test.assertTrue(MonsterRoles.settle(p, true), "A zombie landing under water changes form");
            test.assertValueEqual(MonsterRoles.currentForm(p), MonsterForm.DROWNED, "into a drowned");
            test.assertTrue(!MonsterRoles.settle(p, true), "A drowned landing under water again stays one");
            MonsterRoles.select(p, "creeper");
            test.assertTrue(!MonsterRoles.settle(p, true), "A creeper never changes");
        } finally { MonsterRoleGameTest.remove(p); }
        test.succeed();
    }

    @GameTest
    public void aFormWithoutItsOwnSkinWearsItsRolesBaseSkin(GameTestHelper test) {
        for (var form : MonsterForm.values())
            test.assertTrue(textures(form.id()) != null, "Every form has a skin to wear: " + form);
        // None of the four has a signed skin yet (monster-skins.json); each wears its role's base skin until one is added.
        test.assertValueEqual(textures("husk"), textures("zombie"), "A husk wears the zombie skin");
        test.assertValueEqual(textures("zombified_piglin"), textures("zombie"), "A zombified piglin wears the zombie skin");
        test.assertValueEqual(textures("bogged"), textures("skeleton"), "A bogged wears the skeleton skin");
        test.assertValueEqual(textures("parched"), textures("skeleton"), "A parched wears the skeleton skin");
        test.assertTrue(!textures("drowned").equals(textures("zombie")), "A form with its own skin still wears it");
        test.succeed();
    }
    private static String textures(String kind) {
        return dev.hardrush.disguise.MonsterSkin.head(kind).partialProfile().properties().get("textures").iterator().next().value();
    }

    /** Draws the bow of a fresh {@code form} body made ready by {@code setup}, looses one arrow and hands it to {@code check}; with {@code hardRest}, checks the native rest. */
    private static void shootOnce(GameTestHelper test, MonsterForm form, Consumer<ServerPlayer> setup, java.util.function.BiConsumer<ServerPlayer, AbstractArrow> check, Integer hardRest) {
        var p = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> {
            for (var arrow : p.level().getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(80))) if (arrow.getOwner() == p) arrow.discard();
            MonsterRoleGameTest.remove(p);
        };
        try {
            MonsterRoles.select(p, "skeleton");
            MonsterRoles.convert(p, form);
            setup.accept(p);
            p.getInventory().setSelectedSlot(0);
            test.assertTrue(MonsterRoles.useControl(p, InteractionHand.MAIN_HAND), "The bow draws");
        } catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
        test.runAfterDelay(21, () -> {
            int rest;
            try {
                p.releaseUsingItem();
                var arrows = p.level().getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(5)).stream().filter(a -> a.getOwner() == p).toList();
                test.assertValueEqual(arrows.size(), 1, "One arrow");
                check.accept(p, arrows.getFirst());
                var model = (MonsterSkeletonAccessor) MonsterRoles.nativeModel(p);
                boolean hard = p.level().getDifficulty() == Difficulty.HARD;
                rest = hard ? model.hardrush$getHardAttackInterval() : model.hardrush$getAttackInterval();
                if (hardRest != null) test.assertValueEqual(rest, hard ? hardRest : 70, "Bogged and parched rest 50/70 ticks natively");
            } catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
            test.runAfterDelay(rest - 3, () -> {
                try { test.assertTrue(!MonsterRoles.useControl(p, InteractionHand.MAIN_HAND), "The bow rests the native interval"); }
                catch (RuntimeException | AssertionError error) { cleanup.run(); throw error; }
                test.runAfterDelay(6, () -> {
                    try { test.assertTrue(MonsterRoles.useControl(p, InteractionHand.MAIN_HAND), "Then it draws again"); }
                    finally { cleanup.run(); }
                    test.succeed();
                });
            });
        });
    }

    private static void assertOwnEffect(GameTestHelper test, AbstractArrow arrow, MonsterForm form, int ticks) {
        var own = form.arrowEffect();
        var contents = arrow.getPickupItemStackOrigin().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        var effects = contents.customEffects().stream().filter(effect -> effect.is(own.effect())).toList();
        test.assertValueEqual(effects.size(), 1, "The form's own effect is on the arrow once: " + form);
        test.assertValueEqual(effects.getFirst().getDuration(), ticks, "Its duration: " + form);
    }

    private static void assertParks(GameTestHelper test, ServerPlayer p, MonsterForm form, MonsterRole role) {
        var parked = MonsterParking.capture(p);
        var body = MonsterParking.decode(p, parked, role).orElseThrow();
        test.assertValueEqual(body.form(), form, "The variant parks as itself: " + form);
        test.assertValueEqual(MonsterParking.describe(parked).orElseThrow(), form.label, "The camp names the parked " + form);
    }

    private static void buyArrows(ServerPlayer p) {
        p.getInventory().setItem(9, MonsterItems.bind(p, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.SLOWNESS).copyWithCount(3), MonsterRoles.SHOP_ARROW));
        p.getInventory().setItem(10, MonsterItems.bind(p, PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON).copyWithCount(3), MonsterRoles.SHOP_ARROW));
    }
    private static boolean isTipped(ItemStack stack, net.minecraft.core.Holder<Potion> potion) {
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        return stack.is(Items.TIPPED_ARROW) && contents != null && contents.is(potion);
    }
    private static int count(ServerPlayer p, net.minecraft.core.Holder<Potion> potion) {
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (isTipped(p.getInventory().getItem(i), potion)) count += p.getInventory().getItem(i).getCount();
        return count;
    }
    private static void setCount(ServerPlayer p, net.minecraft.core.Holder<Potion> potion, int count) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (isTipped(p.getInventory().getItem(i), potion)) p.getInventory().getItem(i).setCount(count);
    }
    private static int eggs(ServerPlayer p, Item egg) {
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            var stack = p.getInventory().getItem(i);
            if (stack.is(egg) && (MonsterItems.matches(p, stack, MonsterCompanions.BABY_EGG) || MonsterItems.matches(p, stack, MonsterCompanions.ADULT_EGG))) count += stack.getCount();
        }
        return count;
    }
}
