package dev.hardrush.monster;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.PotionBox;
import dev.hardrush.core.round.PotionBrew;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.gametest.MonsterRoleGameTest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

/**
 * docs/plan/女巫药水与药水盲盒.md: the witch's bottles are drunk the native way. The undead heal from harming, the empty
 * bottle never comes back, a blind box brews on the spot, and a return parks every timed effect, the bad included.
 * The draws themselves are covered by {@code PotionBoxTest}.
 */
public final class MonsterPotionsGameTest {
    /** Native drinking takes 32 ticks; a margin covers the tick the use starts in. */
    private static final int DRINK_TICKS = 40;
    private static final RulesConfig RULES = RulesConfig.defaults();

    @GameTest(maxTicks = 400)
    public void theUndeadHealFromHarmingAndTheCreeperFromHealing(GameTestHelper test) {
        var zombie = MonsterRoleGameTest.actor(test);
        var creeper = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> { MonsterRoleGameTest.remove(zombie); MonsterRoleGameTest.remove(creeper); };
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(zombie, "zombie");
                    creeper.teleportTo(zombie.getX(), zombie.getY(), zombie.getZ() + 6);
                    MonsterRoles.select(creeper, "creeper");
                    for (var player : java.util.List.of(zombie, creeper)) {
                        player.setHealth(8);
                        var role = player == creeper ? MonsterRole.CREEPER : MonsterRole.ZOMBIE;
                        player.setItemInHand(InteractionHand.MAIN_HAND, MonsterPotions.sortieBottle(player, ShopOffer.INSTANT_HEALTH, RULES, role));
                    }
                    test.assertTrue(zombie.getMainHandItem().getHoverName().getString().equals("伤害药水"), "The undead are sold harming");
                    test.assertTrue(creeper.getMainHandItem().getHoverName().getString().equals("治疗药水"), "The creeper is sold healing");
                    test.assertTrue(MonsterRoles.useControl(zombie, InteractionHand.MAIN_HAND), "A witch potion starts drinking");
                    test.assertTrue(MonsterRoles.useControl(creeper, InteractionHand.MAIN_HAND), "The creeper drinks too, igniter aside");
                    test.assertTrue(MonsterPotions.drinking(zombie) && MonsterPotions.drinking(creeper), "Both are drinking natively");
                }))
                .thenIdle(DRINK_TICKS)
                .thenExecute(guarded(cleanup, () -> {
                    test.assertTrue(zombie.getHealth() >= 12, "Harming heals a zombie by native rules: " + zombie.getHealth());
                    test.assertTrue(creeper.getHealth() >= 12, "Healing heals a creeper: " + creeper.getHealth());
                    test.assertTrue(zombie.getMainHandItem().isEmpty() && !zombie.getInventory().contains(stack -> stack.is(Items.GLASS_BOTTLE)),
                            "The empty bottle never comes back");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aBlindBoxBrewsWhenDrunkAndLettingGoStopsIt(GameTestHelper test) {
        var zombie = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> MonsterRoleGameTest.remove(zombie);
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(zombie, "zombie");
                    zombie.setItemInHand(InteractionHand.MAIN_HAND, MonsterPotions.sortieBottle(zombie, ShopOffer.EPIC_POTION_BOX, RULES, MonsterRole.ZOMBIE));
                    zombie.setItemInHand(InteractionHand.OFF_HAND, MonsterPotions.sortieBottle(zombie, ShopOffer.COMMON_POTION_BOX, RULES, MonsterRole.ZOMBIE));
                    zombie.removeAllEffects();
                    test.assertTrue(Boolean.TRUE.equals(zombie.getMainHandItem().get(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                            "A blind box glints like the monster box");
                    test.assertTrue(MonsterRoles.useControl(zombie, InteractionHand.OFF_HAND), "A box starts drinking");
                    zombie.releaseUsingItem();
                    test.assertFalse(zombie.isUsingItem(), "Letting go stops a witch potion as it would anyone's");
                    test.assertFalse(zombie.getOffhandItem().isEmpty(), "A drink let go of is kept");
                    test.assertTrue(MonsterRoles.useControl(zombie, InteractionHand.MAIN_HAND), "The epic box starts drinking");
                }))
                .thenIdle(DRINK_TICKS)
                .thenExecute(guarded(cleanup, () -> {
                    test.assertTrue(zombie.getMainHandItem().isEmpty(), "The box is drunk");
                    // An epic box always brews an epic and a rare dose; only the rare one may be instant.
                    test.assertTrue(zombie.getActiveEffects().stream().anyMatch(effect -> effect.is(MobEffects.INVISIBILITY)
                                    || effect.is(MobEffects.WEAVING) || effect.is(MobEffects.OOZING) || effect.is(MobEffects.INFESTED)
                                    || effect.is(MobEffects.WIND_CHARGED)),
                            "The epic dose was brewed: " + zombie.getActiveEffects());
                    test.assertFalse(zombie.hasEffect(MobEffects.REGENERATION), "The undead never draw regeneration");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aReturnParksEveryTimedEffectTheBadIncluded(GameTestHelper test) {
        var skeleton = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> MonsterRoleGameTest.remove(skeleton);
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(skeleton, "skeleton");
                    skeleton.addEffect(new MobEffectInstance(MobEffects.GLOWING, 400));
                    skeleton.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 2));
                    skeleton.setAbsorptionAmount(3);
                    var body = MonsterParking.decode(skeleton, MonsterParking.capture(skeleton), MonsterRole.SKELETON).orElseThrow();
                    test.assertTrue(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.GLOWING)), "A bad effect is parked too");
                    test.assertTrue(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.ABSORPTION) && effect.getAmplifier() == 2),
                            "A witch potion's effect keeps its level");
                    test.assertFalse(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.NIGHT_VISION)),
                            "An endless effect is no potion's and is not parked");
                    test.assertValueEqual(body.absorption(), 3.0F, "Only what is left of the yellow hearts parks, never a refill");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    /** docs/plan/女巫药水与药水盲盒.md 9.3: a parting gift is endless, parks with the body and is back on the next sortie. */
    @GameTest(maxTicks = 400)
    public void aPartingGiftLastsUntilDeathAndSurvivesAReturn(GameTestHelper test) {
        var zombie = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> MonsterRoleGameTest.remove(zombie);
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(zombie, "zombie");
                    test.assertTrue(zombie.getEffect(MobEffects.NIGHT_VISION) != null
                            && MonsterRoles.intrinsic(zombie.getEffect(MobEffects.NIGHT_VISION)), "The body sees in the dark before parking");
                    MonsterPotions.apply(zombie, MonsterRole.ZOMBIE,
                            new PotionBox.Dose(PotionBrew.OOZING, PotionBrew.Grade.EPIC, 0, PotionBox.UNTIL_DEATH));
                    var drunk = zombie.getEffect(MobEffects.OOZING);
                    test.assertTrue(drunk != null && drunk.isInfiniteDuration() && drunk.getAmplifier() == 0, "A parting gift is endless, level I");
                    var body = MonsterParking.decode(zombie, MonsterParking.capture(zombie), MonsterRole.ZOMBIE).orElseThrow();
                    var parked = body.effects().stream().filter(effect -> effect.is(MobEffects.OOZING)).findFirst().orElse(null);
                    test.assertTrue(parked != null && parked.isInfiniteDuration(), "An endless parting gift parks and decodes as endless");
                    test.assertFalse(body.effects().stream().anyMatch(effect -> effect.is(MobEffects.NIGHT_VISION)),
                            "The body's own night vision stays out of the parking, endless as it is");
                    zombie.removeAllEffects();
                    zombie.addEffect(parked);
                    test.assertTrue(zombie.getEffect(MobEffects.OOZING).isInfiniteDuration(), "A resumed parting gift is still endless");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    /** A body that already sees in the dark refuses more night vision, so its own keeps reading as its own (#95). */
    @GameTest(maxTicks = 200)
    public void aMonsterRefusesNightVisionOnTopOfItsOwn(GameTestHelper test) {
        var zombie = MonsterRoleGameTest.actor(test);
        Runnable cleanup = () -> MonsterRoleGameTest.remove(zombie);
        test.startSequence()
                .thenIdle(80)
                .thenExecute(guarded(cleanup, () -> {
                    MonsterRoles.select(zombie, "zombie");
                    test.assertTrue(MonsterRoles.intrinsic(zombie.getEffect(MobEffects.NIGHT_VISION)), "The body sees in the dark");
                    test.assertFalse(zombie.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200)), "More night vision adds nothing");
                    test.assertTrue(MonsterRoles.intrinsic(zombie.getEffect(MobEffects.NIGHT_VISION)),
                            "The body's own night vision still reads as its own, so no cloud copies it and leaving removes it");
                    cleanup.run();
                }))
                .thenSucceed();
    }

    private static Runnable guarded(Runnable cleanup, Runnable body) {
        return () -> {
            try { body.run(); } catch (RuntimeException | AssertionError failure) { cleanup.run(); throw failure; }
        };
    }
}
