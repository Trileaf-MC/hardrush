package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.RulesConfig;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a sortie actually paid for, counted as worth (issue #106), in milli-coins. */
class MonsterInvestmentTest {
    private static final RulesConfig RULES = RulesConfig.defaults();

    private static MonsterWallet chosen(MonsterRole role, int coins) {
        return MonsterWallet.EMPTY.credit(coins).chooseRole(RULES, role);
    }

    private static MonsterWallet zombie(int coins) { return chosen(MonsterRole.ZOMBIE, coins); }

    @Test void eachEggCountsTheLadderStepItWasBoughtAt() {
        var wallet = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16);
        assertEquals(24_000, wallet.deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
        assertEquals(8_000, zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8)
                .deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
    }

    @Test void onlyWornVaultGearCountsItsKey() {
        var wallet = zombie(100)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .openVault(RULES, ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.HEAD, Set.of(MonsterRole.ZOMBIE), "helmet")
                .openVault(RULES, ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.MAINHAND, Set.of(MonsterRole.SKELETON), "bow")
                // An unopened key is refunded at deployment and never counted.
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY, 8);
        assertEquals(3, wallet.gear().getFirst().paid());
        var deployed = wallet.deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(3_000, deployed.sortieInvestment());
        assertEquals(1, deployed.gear().size(), "the skeleton's bow stays in the camp");
    }

    @Test void theCreeperUnlockCountsOnceForAFreshSortieOnly() {
        var deployed = chosen(MonsterRole.CREEPER, 40).unlockCreeper(40).deploy(RULES, MonsterRole.CREEPER);
        assertEquals(40_000, deployed.sortieInvestment());
        var resumed = deployed.park("creeper-body").deploy(RULES, MonsterRole.CREEPER);
        assertEquals(40_000, resumed.sortieInvestment(), "a resumed sortie does not pay for the unlock again");
        // Without a ticket the creeper's sortie counts nothing for it.
        assertEquals(0, chosen(MonsterRole.CREEPER, 40).deploy(RULES, MonsterRole.CREEPER).sortieInvestment());
    }

    @Test void aFullSoulcatcherCountsATenthOfItsPriceForTheZombieAlone() {
        var wallet = zombie(364);
        for (int price : new int[] {32, 64, 128}) wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_TIER, price);
        for (int price : new int[] {20, 40, 80}) wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_CAPACITY, price);
        assertEquals(0, wallet.coins());
        var deployed = wallet.deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(36_400, deployed.sortieInvestment());
        // Every fresh sortie counts it again; a resumed one does not.
        assertEquals(36_400, deployed.park("zombie-body").deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
        assertEquals(36_400, deployed.endSortie().deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
        assertEquals(0, wallet.chooseRole(RULES, MonsterRole.SKELETON).deploy(RULES, MonsterRole.SKELETON).sortieInvestment());
    }

    @Test void revivalSuppliesNeverCount() {
        var deployed = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.IRON_BUCKET, 15).deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(1, deployed.ordered(ShopOffer.IRON_BUCKET));
        assertEquals(0, deployed.sortieInvestment());
    }

    @Test void resumingKeepsTheInvestmentAndAddsOnlyWhatThePauseBoughtAtItsLadderStep() {
        var deployed = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8).deploy(RULES, MonsterRole.ZOMBIE)
                .creditDamage(UUID.randomUUID(), 2_000, 1000, 20_000);
        var parked = deployed.park("zombie-body");
        assertEquals(8_000, parked.sortieInvestment());
        // The ladder runs on through the pause: the second egg costs, and counts, double.
        var resumed = parked.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16).deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(24_000, resumed.sortieInvestment());
        assertEquals(deployed.sortieDamage(), resumed.sortieDamage());
        // Base 10, 24 coins of investment and 2 points of damage.
        assertEquals(16_800, MonsterWorth.milliLevels(RULES, resumed));
    }

    @Test void cancellingAFreshDeploymentClearsItAndAResumptionReturnsToThePause() {
        var bought = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8);
        assertEquals(0, bought.deploy(RULES, MonsterRole.ZOMBIE).cancelDeploy(RULES).sortieInvestment());

        var parked = bought.deploy(RULES, MonsterRole.ZOMBIE).park("zombie-body")
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .openVault(RULES, ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.HEAD, Set.of(MonsterRole.ZOMBIE), "helmet");
        var resumed = parked.deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(8_000 + 16_000 + 3_000, resumed.sortieInvestment());
        var cancelled = resumed.cancelDeploy(RULES);
        assertEquals(8_000, cancelled.sortieInvestment());
        assertEquals(1, cancelled.ordered(ShopOffer.ADULT_EGG));
        // The egg and the helmet went back with their ladder intact, so trying again counts the same.
        assertEquals(27_000, cancelled.deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
    }

    @Test void theInvestmentLastsUntilTheNextFreshDeployment() {
        var deployed = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8).deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(8_000, deployed.park("zombie-body").sortieInvestment());
        assertEquals(8_000, deployed.keepUnspent().sortieInvestment());
        var ended = deployed.endSortie();
        assertEquals(8_000, ended.sortieInvestment(), "a slain body is settled after its sortie ends");
        assertEquals(8_000, ended.credit(5).spendCoins(2).becameMonster(6).creditDamage(UUID.randomUUID(), 1_000, 1000, 20_000)
                .chooseRole(RULES, MonsterRole.ZOMBIE).sortieInvestment());
        assertEquals(0, ended.deploy(RULES, MonsterRole.ZOMBIE).sortieInvestment());
    }

    @Test void aFreshSortieAfterAResumedOneCountsOnlyWhatItFreezes() {
        var resumed = zombie(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8).deploy(RULES, MonsterRole.ZOMBIE)
                .park("zombie-body").buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16).deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(24_000, resumed.sortieInvestment());
        var next = resumed.endSortie().buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8).deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(8_000, next.sortieInvestment(), "nothing of the resumed sortie is left over");
    }

    @Test void aChargeTheCreeperBroughtBackUnusedCountsItsFirstPriceAgain() {
        var deployed = chosen(MonsterRole.CREEPER, 80).unlockCreeper(40)
                .buy(RULES, MonsterRole.CREEPER, ShopOffer.CHARGED, 40).deploy(RULES, MonsterRole.CREEPER);
        assertEquals(80_000, deployed.sortieInvestment(), "the unlock and the charge");
        var back = deployed.keepUnspent().endSortie();
        assertEquals(1, back.ordered(ShopOffer.CHARGED));
        assertEquals(80_000, back.deploy(RULES, MonsterRole.CREEPER).sortieInvestment(), "the charge counts 40, not 80");
    }

    @Test void roundsWithoutSpoilsCountNothing() {
        var legacy = MonsterWorthTest.withoutSpoils();
        var wallet = MonsterWallet.EMPTY.credit(100).chooseRole(legacy, MonsterRole.ZOMBIE)
                .buy(legacy, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8);
        assertEquals(0, wallet.deploy(legacy, MonsterRole.ZOMBIE).sortieInvestment());
    }
}
