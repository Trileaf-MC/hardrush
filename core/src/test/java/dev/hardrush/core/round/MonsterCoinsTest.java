package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatEvent;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonsterCoinsTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final MonotonicClock clock = () -> 0;

    private static MonsterWallet chosen(int coins) {
        return MonsterWallet.EMPTY.credit(coins).chooseRole(RULES, MonsterRole.ZOMBIE);
    }

    @Test void campCoinTransfersPreserveOrdersAndAllowGiftsBeforeRevival() {
        var wallet = chosen(90).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3);
        var engine = engine(humanParticipant(), deployedMonster().withWallet(wallet));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, monster, 1, 0, 1));
        var before = engine.view().participants().get(monster);
        accept(engine, engine.proposeMonsterCoinDrop(UUID.randomUUID(), round, monster, 1, 3, 64));
        var dropped = engine.view().participants().get(monster);
        assertEquals(before.wallet().spendCoins(64), dropped.wallet());
        assertEquals(1, dropped.revivalCharges(), "Coin transfers do not consume revival charges");
        accept(engine, engine.proposeMonsterCoinPickup(UUID.randomUUID(), round, monster, 1, 3, 64));
        assertEquals(before.wallet(), engine.view().participants().get(monster).wallet());
    }

    @Test void coinTransfersRejectOverdrawStaleBodiesHumansDeployedMonstersAndOverflow() {
        var engine = engine(humanParticipant(), deployedMonster().withWallet(chosen(10)));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterCoinDrop(UUID.randomUUID(), round, monster, 1, 3, 1));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeMonsterCoinDrop(UUID.randomUUID(), round, monster, 1, 3, 11));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeMonsterCoinDrop(UUID.randomUUID(), round, monster, 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeMonsterCoinPickup(UUID.randomUUID(), round, monster, 1, 3, -1));
        assertThrows(IllegalArgumentException.class, () -> engine.proposeMonsterCoinPickup(UUID.randomUUID(), round, monster, 1, 3, Integer.MAX_VALUE));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterCoinDrop(UUID.randomUUID(), UUID.randomUUID(), monster, 1, 3, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterCoinDrop(UUID.randomUUID(), round, monster, 2, 3, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterCoinPickup(UUID.randomUUID(), round, monster, 1, 4, 1));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterCoinPickup(UUID.randomUUID(), round, human, 1, 0, 1));
        assertEquals(10, engine.view().participants().get(monster).wallet().coins());
        assertFalse(engine.pending());
    }

    @Test void pricesDoubleWithinOneSortieAndStopAtTheLimit() {
        var wallet = chosen(100);
        for (int price : new int[] {8, 16, 32}) {
            assertEquals(price, wallet.nextPrice(RULES, ShopOffer.ADULT_EGG));
            wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, price);
        }
        assertEquals(44, wallet.coins());
        assertEquals(3, wallet.ordered(ShopOffer.ADULT_EGG));
        assertTrue(wallet.soldOut(RULES, ShopOffer.ADULT_EGG));
        var full = wallet;
        assertThrows(IllegalStateException.class, () -> full.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 64));
        // The next sortie window starts again from the base price.
        var next = wallet.deploy(RULES, MonsterRole.ZOMBIE).endSortie();
        assertEquals(8, next.nextPrice(RULES, ShopOffer.ADULT_EGG));
    }

    @Test void vaultKeysKeepOnePriceUpToTheCampGearCap() {
        var wallet = chosen(500);
        assertEquals(MonsterWallet.MAX_GEAR, MonsterCoins.limit(RULES, ShopOffer.TRIAL_KEY));
        assertEquals(MonsterWallet.MAX_GEAR, MonsterCoins.limit(RULES, ShopOffer.OMINOUS_KEY));
        for (int i = 0; i < MonsterWallet.MAX_GEAR; i++) {
            assertEquals(3, wallet.nextPrice(RULES, ShopOffer.TRIAL_KEY));
            wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3);
        }
        assertTrue(wallet.soldOut(RULES, ShopOffer.TRIAL_KEY));
        assertEquals(500 - 3 * MonsterWallet.MAX_GEAR, wallet.coins());
        for (int i = 0; i < 3; i++) wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY, 8);
        assertEquals(8, wallet.nextPrice(RULES, ShopOffer.OMINOUS_KEY));
        assertEquals(3 * MonsterWallet.MAX_GEAR + 3 * 8, MonsterCoins.refund(RULES, ShopOffer.TRIAL_KEY, 16, 16)
                + MonsterCoins.refund(RULES, ShopOffer.OMINOUS_KEY, 3, 3));
    }

    @Test void onlyFlatPricedKeysMayBeBoughtBeyondTheDoublingLadderCap() {
        var keys = new TreeMap<>(RULES.combatValues());
        keys.put("coin.limit.ominousKey", (double) MonsterWallet.MAX_GEAR + 1);
        assertThrows(IllegalArgumentException.class, () -> rules(keys));
        var eggs = new TreeMap<>(RULES.combatValues());
        eggs.put("coin.limit.adultEgg", 9.0);
        assertThrows(IllegalArgumentException.class, () -> rules(eggs));
    }

    @Test void purchasesRequireAChosenRoleASellableOfferTheShownPriceAndEnoughCoins() {
        var unchosen = MonsterWallet.EMPTY.credit(50);
        assertThrows(IllegalStateException.class, () -> unchosen.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8));
        var wallet = chosen(5);
        assertThrows(IllegalStateException.class, () -> wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8));
        assertThrows(IllegalStateException.class, () -> chosen(50).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.CHARGED, 40));
        assertThrows(IllegalStateException.class, () -> chosen(50).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16));
    }

    @Test void changingRoleRefundsWhatTheNewRoleCannotUseAtThePricePaid() {
        var wallet = chosen(100)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 16)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SWIFTNESS, 10)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3);
        var skeleton = wallet.chooseRole(RULES, MonsterRole.SKELETON);
        assertEquals(100 - 10 - 3, skeleton.coins());
        assertEquals(0, skeleton.ordered(ShopOffer.ADULT_EGG));
        assertEquals(1, skeleton.ordered(ShopOffer.SWIFTNESS));
        assertEquals(1, skeleton.ordered(ShopOffer.TRIAL_KEY));
        var creeper = skeleton.chooseRole(RULES, MonsterRole.CREEPER);
        assertEquals(90, creeper.coins());
        assertEquals(3, creeper.nextPrice(RULES, ShopOffer.TRIAL_KEY));
    }

    @Test void deploymentFreezesTheKitRefundsUnopenedKeysAndCancellationRestoresIt() {
        var wallet = chosen(200)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.BABY_EGG, 20)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.HEAD, Set.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON), "helmet")
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.MAINHAND, Set.of(MonsterRole.SKELETON), "bow");
        assertEquals(200 - 20 - 3 - 3, wallet.coins());
        wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY, 8)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY, 8);
        var deployed = wallet.deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(174, deployed.coins(), "unopened ominous keys are refunded at the price paid");
        assertEquals(1, deployed.carried(ShopOffer.BABY_EGG));
        assertEquals(List.of("helmet"), deployed.activeGear().stream().map(MonsterWallet.GearPiece::item).toList());
        assertEquals(List.of("bow"), deployed.gear().stream().map(MonsterWallet.GearPiece::item).toList());
        var cancelled = deployed.cancelDeploy();
        assertEquals(1, cancelled.ordered(ShopOffer.BABY_EGG));
        assertEquals(List.of("helmet", "bow"), cancelled.gear().stream().map(MonsterWallet.GearPiece::item).toList());
        var ended = deployed.endSortie();
        assertTrue(ended.active().isEmpty());
        assertTrue(ended.activeGear().isEmpty());
        assertTrue(ended.bought().isEmpty());
        assertEquals(List.of("bow"), ended.gear().stream().map(MonsterWallet.GearPiece::item).toList());
    }

    @Test void damageAccumulatesFractionsCapsPerHumanAndAppliesTheBountyAfterTheCap() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var wallet = MonsterWallet.EMPTY.creditDamage(a, 600, 1000, 20_000);
        assertEquals(0, wallet.coins());
        wallet = wallet.creditDamage(a, 600, 1000, 20_000);
        assertEquals(1, wallet.coins());
        wallet = wallet.creditDamage(a, 30_000, 1000, 20_000);
        assertEquals(20, wallet.coins());
        assertEquals(20, wallet.creditDamage(a, 5_000, 1000, 20_000).coins());
        // Another human has an independent cap; the End bounty doubles what was counted.
        wallet = wallet.creditDamage(b, 25_000, 2000, 20_000);
        assertEquals(60, wallet.coins());
        assertEquals(3, MonsterWallet.EMPTY.creditDamage(a, 2_000, 1500, 20_000).coins());
    }

    @Test void everyMonsterLifeStartsWithTheGiftAndRevivalEmptiesTheWallet() {
        var engine = engine(humanParticipant());
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, human, 1, 0, 1));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(human, 1, 0, Optional.empty())), null));
        var wallet = engine.committed().participants().get(human).wallet();
        assertEquals(6, wallet.coins());
        assertFalse(wallet.roleChosen());
        accept(engine, engine.proposeAdmissionRevival(UUID.randomUUID(), round, human, 1, 0));
        var revived = engine.committed().participants().get(human);
        assertEquals(MonsterWallet.EMPTY, revived.wallet(), "Coins and paid goods never outlive the monster");
        assertEquals(0, revived.lifeHumanNanos(), "The revived life starts its survival time from zero");
        assertEquals(6, revived.wallet().becameMonster(6).coins(), "The next monster life gets the gift again");
    }

    @Test void aKillPaysARevivalAndSendsTheHunterBack() {
        var other = new Participant(UUID.randomUUID(), "Other", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false);
        var engine = engine(humanParticipant(), other, deployedMonster());
        var bite = origin(monster, ParticipantRole.MONSTER_DEPLOYED, 3, AttackKind.MELEE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(human, 1, 0, Optional.of(lethal(human, bite)))), null));
        var owner = engine.committed().participants().get(monster);
        assertEquals(0, owner.wallet().coins(), "Killing a human earns no coins");
        assertEquals(1, owner.revivalCharges());
        assertEquals(ParticipantRole.MONSTER_LOBBY, owner.role(), "The hunter is sent back to the camp at once");
        for (var attempt : List.<java.util.function.Supplier<RoundTransition>>of(
                () -> engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3),
                () -> engine.proposeMonsterRole(UUID.randomUUID(), round, monster, 1, 3, MonsterRole.SKELETON),
                () -> engine.proposeShopPurchase(UUID.randomUUID(), round, monster, 1, 3, ShopOffer.SWIFTNESS, 10))) {
            var refused = assertThrows(IllegalStateException.class, attempt::get);
            assertEquals(RoundEngine.REVIVAL_FIRST, refused.getMessage());
        }
    }

    @Test void monsterDeathRewardUsesNativePlayerMemoryRegardlessOfTheFinalDamageSource() {
        for (var cause : List.of(Optional.<CombatEvent>empty(),
                Optional.of(lethal(monster, origin(human, ParticipantRole.HUMAN_ACTIVE, 0, AttackKind.MELEE))),
                Optional.of(lethal(monster, origin(human, ParticipantRole.HUMAN_ACTIVE, 0, AttackKind.SUMMON))),
                Optional.of(lethal(monster, origin(UUID.randomUUID(), ParticipantRole.MONSTER_DEPLOYED, 1, AttackKind.MELEE))))) {
            var engine = engine(humanParticipant(), deployedMonster());
            accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                    List.of(new RoundDeath(monster, 1, 3, cause, true)), null));
            var victim = engine.committed().participants().get(monster);
            assertEquals(8, victim.wallet().coins(), "Native player memory alone qualifies the deployed monster");
            assertEquals(ParticipantRole.MONSTER_LOBBY, victim.role());
        }
    }

    @Test void absentOrExpiredNativePlayerMemoryCannotBeReplacedByCombatAttribution() {
        for (var cause : List.of(Optional.<CombatEvent>empty(),
                Optional.of(lethal(monster, origin(human, ParticipantRole.HUMAN_ACTIVE, 0, AttackKind.MELEE))))) {
            var engine = engine(humanParticipant(), deployedMonster());
            accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                    List.of(new RoundDeath(monster, 1, 3, cause, false)), null));
            assertEquals(0, engine.committed().participants().get(monster).wallet().coins());
        }
    }

    @Test void recentPlayerDamageDoesNotGiveHumansTheMonsterDeathReward() {
        var engine = engine(humanParticipant(), deployedMonster());
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(human, 1, 0, Optional.empty(), true)), null));
        assertEquals(6, engine.committed().participants().get(human).wallet().coins(), "Only the transformation gift applies");
    }

    @Test void survivalTimeCountsOnlyTheCurrentOnlineLife() {
        var clock = new long[] {0};
        Map<UUID, Participant> players = new LinkedHashMap<>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 5_000, 50, 2, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), () -> clock[0]);
        clock[0] = 7_000;
        accept(engine, engine.proposeOnline(UUID.randomUUID(), human, "Human", true));
        assertEquals(0, engine.committed().participants().get(human).lifeHumanNanos(), "Offline time is not survival");
        clock[0] = 10_000;
        accept(engine, engine.proposeCheckpoint(UUID.randomUUID(), false));
        var p = engine.committed().participants().get(human);
        assertEquals(3_000, p.lifeHumanNanos());
        assertEquals(8_000, p.humanNanos(), "The cross-life total keeps accumulating separately");
    }

    @Test void aDeathOfTheJustRecalledSortieIsSettledButOtherStaleIdentitiesAreRejected() {
        var engine = engine(humanParticipant(), deployedMonster());
        var bite = origin(monster, ParticipantRole.MONSTER_DEPLOYED, 3, AttackKind.MELEE);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(human, 1, 0, Optional.of(lethal(human, bite)))), null));
        var recalled = engine.committed().participants().get(monster);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 3, Optional.empty(), true)), null));
        assertEquals(recalled, engine.committed().participants().get(monster), "The ended sortie is not settled twice");
        assertThrows(IllegalStateException.class, () -> engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 1, 2, Optional.empty())), null), "An older sortie stays stale");
        assertThrows(IllegalStateException.class, () -> engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(monster, 2, 3, Optional.empty())), null), "A different life stays stale");
        assertFalse(engine.pending());
    }

    @Test void bufferedDamageSkipsStaleEntriesInsteadOfFailing() {
        var engine = engine(humanParticipant(), deployedMonster());
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round, List.of(
                new RoundEngine.DamageCoins(monster, 1, 3, human, 1500, 4_000),
                new RoundEngine.DamageCoins(monster, 1, 2, human, 1000, 9_000),
                new RoundEngine.DamageCoins(UUID.randomUUID(), 1, 3, human, 1000, 9_000))));
        assertEquals(6, engine.committed().participants().get(monster).wallet().coins());
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), UUID.randomUUID(), List.of(
                new RoundEngine.DamageCoins(monster, 1, 3, human, 1000, 9_000))));
        assertEquals(6, engine.committed().participants().get(monster).wallet().coins());
    }

    @Test void snapshotsRoundTripTheWallet() {
        var wallet = chosen(80).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 3)
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.CHEST, Set.of(MonsterRole.ZOMBIE), "{id:\"chest\"}")
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 8)
                .creditDamage(human, 1_234, 1500, 20_000);
        var player = deployedMonster().withWallet(wallet.deploy(RULES, MonsterRole.ZOMBIE));
        var snapshot = new RoundSnapshot(round, 1, 2, 3, RULES, RoundPhase.RUNNING, PauseReason.NONE, 0, 0,
                Map.of(monster, player), false, false, null);
        assertEquals(snapshot, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot)));
    }

    @Test void pricesMustFitTwoEmeraldStacksAfterDoubling() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put("coin.price.babyEgg", 70.0);
        assertThrows(IllegalArgumentException.class, () -> new RulesConfig(RULES.schemaVersion(), RULES.mode(),
                RULES.minecraftVersion(), RULES.difficulty(), RULES.generationRule(), values,
                RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()));
    }

    @Test void soulcatcherUpgradesLastTheWholeMonsterLifeAndAreNeverQueued() {
        var wallet = chosen(400);
        for (int price : new int[] {32, 64, 128}) wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_TIER, price);
        wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_CAPACITY, 20);
        assertEquals(3, wallet.owned(ShopOffer.SOULCATCHER_TIER));
        assertEquals(0, wallet.ordered(ShopOffer.SOULCATCHER_TIER));
        var topped = wallet;
        var error = assertThrows(IllegalStateException.class,
                () -> topped.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_TIER, 256));
        assertTrue(error.getMessage().contains("升到顶"));
        // Deploying, ending sorties and choosing a role that cannot use it neither spend nor refund an upgrade.
        wallet = wallet.deploy(RULES, MonsterRole.ZOMBIE).endSortie().chooseRole(RULES, MonsterRole.SKELETON)
                .chooseRole(RULES, MonsterRole.ZOMBIE).deploy(RULES, MonsterRole.ZOMBIE).endSortie();
        assertEquals(3, wallet.owned(ShopOffer.SOULCATCHER_TIER));
        assertEquals(1, wallet.owned(ShopOffer.SOULCATCHER_CAPACITY));
        assertEquals(400 - 32 - 64 - 128 - 20, wallet.coins());
        assertEquals(40, wallet.nextPrice(RULES, ShopOffer.SOULCATCHER_CAPACITY), "The ladder continues across sorties");
        assertThrows(IllegalStateException.class, () -> chosen(100).buy(RULES, MonsterRole.SKELETON, ShopOffer.SOULCATCHER_TIER, 32));
    }

    @Test void roundsSavedBeforeTheSoulcatcherNeitherCarryNorSellIt() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.contains("soulcatcher"));
        var legacy = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertFalse(MonsterCoins.soulcatcher(legacy));
        assertFalse(MonsterCoins.offered(legacy, ShopOffer.SOULCATCHER_TIER));
        assertFalse(MonsterCoins.offered(legacy, ShopOffer.MONSTER_BOX), "The box draws by the soulcatcher's tier");
        assertTrue(MonsterCoins.soulcatcher(RULES));
        assertTrue(MonsterCoins.offered(RULES, ShopOffer.MONSTER_BOX));
        var error = assertThrows(IllegalStateException.class,
                () -> MonsterWallet.EMPTY.credit(30).chooseRole(legacy, MonsterRole.ZOMBIE)
                        .buy(legacy, MonsterRole.ZOMBIE, ShopOffer.SOULCATCHER_TIER, 30));
        assertTrue(error.getMessage().contains("不出售"));
    }

    @Test void pendingRulesStillOnTheOldKeyPricesFollowTheCheaperOnes() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put(RulesConfig.DEFAULTS_REVISION_KEY, 2.0);
        values.put("coin.price.trialKey", 15.0);
        values.put("coin.price.ominousKey", 40.0);
        // Chosen by an operator after revision 2: a later migration must not replay revision 2 over it.
        values.put("monster.deployCooldownMillis", 30_000.0);
        values.put("coin.limit.trialKey", 2.0);
        values.put("coin.limit.ominousKey", 1.0);
        var next = RulesConfig.decode(rules(values).encode()).forNewRound();
        assertEquals(3.0, next.combatValues().get("coin.price.trialKey"));
        assertEquals(8.0, next.combatValues().get("coin.price.ominousKey"));
        assertEquals(16.0, next.combatValues().get("coin.limit.trialKey"));
        assertEquals(16.0, next.combatValues().get("coin.limit.ominousKey"));
        assertEquals(30_000.0, next.combatValues().get("monster.deployCooldownMillis"));
        assertEquals(RULES.combatValues().get(RulesConfig.DEFAULTS_REVISION_KEY), next.combatValues().get(RulesConfig.DEFAULTS_REVISION_KEY));

        values.remove(RulesConfig.DEFAULTS_REVISION_KEY); // stored before revisions: every step replays once
        values.put("coin.killedByHuman", 5.0);
        values.put("coin.price.trialKey", 20.0); // an operator's own price is kept
        var legacy = rules(values).forNewRound();
        assertNull(legacy.combatValues().get("coin.killedByHuman"), "Retired: the killer's stars decide it");
        assertEquals(20.0, legacy.combatValues().get("coin.price.trialKey"));
        assertEquals(8.0, legacy.combatValues().get("coin.price.ominousKey"));

        var operator = new TreeMap<>(RULES.combatValues());
        operator.put(RulesConfig.DEFAULTS_REVISION_KEY, 4.0);
        operator.put("coin.price.ominousKey", 10.0); // an operator's own key price and limit survive revision 5
        operator.put("coin.limit.ominousKey", 3.0);
        var kept = rules(operator).forNewRound();
        assertEquals(10.0, kept.combatValues().get("coin.price.ominousKey"));
        assertEquals(3.0, kept.combatValues().get("coin.limit.ominousKey"));
        assertEquals(3.0, kept.combatValues().get("coin.price.trialKey"));
        assertEquals(legacy, legacy.forNewRound(), "Migrated rules are stable");
    }

    @Test void monsterBoxesDoubleToAHundredAndOnlyZombiesBuyThem() {
        var wallet = chosen(200);
        for (int price : new int[] {25, 50, 100}) wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.MONSTER_BOX, price);
        assertEquals(3, wallet.ordered(ShopOffer.MONSTER_BOX));
        assertTrue(wallet.soldOut(RULES, ShopOffer.MONSTER_BOX));
        assertEquals(3, wallet.deploy(RULES, MonsterRole.ZOMBIE).carried(ShopOffer.MONSTER_BOX));
        assertThrows(IllegalStateException.class, () -> chosen(100).chooseRole(RULES, MonsterRole.SKELETON)
                .buy(RULES, MonsterRole.SKELETON, ShopOffer.MONSTER_BOX, 25));
    }

    @Test void revisionSixRaisesEggAndSoulcatcherPricesStillOnTheOldDefaults() {
        // Already on revision 5 (the key prices): only revision 6 may run, and it must still run.
        var values = new TreeMap<>(RULES.combatValues());
        values.put(RulesConfig.DEFAULTS_REVISION_KEY, 5.0);
        values.put("coin.price.adultEgg", 6.0);
        values.put("coin.price.babyEgg", 18.0); // an operator's own price is kept
        values.put("coin.price.soulcatcherTier", 30.0);
        values.put("coin.price.trialKey", 5.0); // chosen after revision 5, so revision 5 never replays over it
        values.remove("coin.price.monsterBox"); values.remove("coin.limit.monsterBox");
        var next = rules(values).forNewRound();
        assertEquals(8.0, next.combatValues().get("coin.price.adultEgg"));
        assertEquals(18.0, next.combatValues().get("coin.price.babyEgg"));
        assertEquals(32.0, next.combatValues().get("coin.price.soulcatcherTier"));
        assertEquals(5.0, next.combatValues().get("coin.price.trialKey"));
        assertEquals(25.0, next.combatValues().get("coin.price.monsterBox"));
        assertEquals(3.0, next.combatValues().get("coin.limit.monsterBox"));
        assertEquals(6.0, next.combatValues().get(RulesConfig.DEFAULTS_REVISION_KEY));

        // From revision 4 both steps run in order.
        values.put(RulesConfig.DEFAULTS_REVISION_KEY, 4.0);
        values.put("coin.price.adultEgg", 6.0);
        var both = rules(values).forNewRound();
        assertEquals(3.0, both.combatValues().get("coin.price.trialKey"));
        assertEquals(8.0, both.combatValues().get("coin.price.adultEgg"));
        assertEquals(6.0, both.combatValues().get(RulesConfig.DEFAULTS_REVISION_KEY));
    }

    private static RulesConfig rules(Map<String, Double> values) {
        return new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    @Test void roundsWithoutCoinRulesKeepTheEconomyDisabled() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("coin."));
        var legacy = new RulesConfig(RULES.schemaVersion(), GameMode.HARDCORE, RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertFalse(MonsterCoins.enabled(legacy));
        assertFalse(MonsterCoins.enabled(RULES.withMode(GameMode.NORMAL)));
        assertTrue(MonsterCoins.enabled(legacy.forNewRound()));
    }

    /** A human holding a revival totem, killed with {@code coins} in the wallet: a camp monster that may only revive. */
    private RoundEngine totemHolder(int coins) {
        var other = new Participant(UUID.randomUUID(), "Other", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false);
        var engine = engine(humanParticipant().withWallet(MonsterWallet.EMPTY.credit(coins)), other);
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, human, 1, 0, 1));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(new RoundDeath(human, 1, 0, Optional.empty())), null));
        return engine;
    }

    private void buy(RoundEngine engine, ShopOffer offer, int price) {
        accept(engine, engine.proposeShopPurchase(UUID.randomUUID(), round, human, 1, 0, offer, price));
    }

    @Test void aTotemHolderIsSoldRevivalSuppliesAndNothingElse() {
        var engine = totemHolder(400);
        assertFalse(engine.committed().participants().get(human).wallet().roleChosen(), "No role is needed for supplies");
        buy(engine, ShopOffer.BONUS_CHEST, 30);
        buy(engine, ShopOffer.LONGER_BLESSING, 30);
        buy(engine, ShopOffer.LONGER_BLESSING, 60);
        buy(engine, ShopOffer.LONGER_BLESSING, 120);
        assertThrows(IllegalStateException.class, () -> engine.proposeShopPurchase(UUID.randomUUID(), round, human, 1, 0,
                ShopOffer.LONGER_BLESSING, 240), "Five minutes is the cap");
        assertThrows(IllegalStateException.class, () -> engine.proposeShopPurchase(UUID.randomUUID(), round, human, 1, 0,
                ShopOffer.BONUS_CHEST, 60), "One bonus chest");
        var sortieGoods = assertThrows(Refusal.Exception.class, () -> engine.proposeShopPurchase(UUID.randomUUID(), round, human, 1, 0,
                ShopOffer.SWIFTNESS, 10));
        assertEquals(Refusal.REVIVAL_FIRST, sortieGoods.refusal());
        assertEquals(400 + 6 - 30 - 30 - 60 - 120, engine.committed().participants().get(human).wallet().coins());
    }

    @Test void aMonsterWithoutATotemIsNeverSoldRevivalSupplies() {
        var engine = engine(humanParticipant(), deployedMonster().withWallet(chosen(100)));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        assertThrows(IllegalStateException.class, () -> engine.proposeShopPurchase(UUID.randomUUID(), round, monster, 1, 3, ShopOffer.BREAD, 30));
    }

    @Test void revivalCarriesTheSuppliesIntoTheNewLifeUntilTheyLand() {
        var engine = totemHolder(400);
        buy(engine, ShopOffer.BREAD, 30);
        buy(engine, ShopOffer.BREAD, 60);
        buy(engine, ShopOffer.BONUS_CHEST, 30);
        buy(engine, ShopOffer.LONGER_BLESSING, 30);
        accept(engine, engine.proposeAdmissionRevival(UUID.randomUUID(), round, human, 1, 0));
        var revived = engine.committed().participants().get(human);
        assertEquals(MonsterWallet.EMPTY, revived.wallet(), "Leftover coins still do not outlive the monster");
        assertEquals(Map.of(ShopOffer.BREAD, 2, ShopOffer.BONUS_CHEST, 1, ShopOffer.LONGER_BLESSING, 1), revived.witch().supplyOf(2));
        assertTrue(revived.witch().supplyOf(1).isEmpty());
        assertTrue(revived.witch().landingPending(2));
        assertEquals(160_000, revived.witch().blessingMillis(2), "90 s and one 70 s step");
        assertThrows(IllegalStateException.class, () -> engine.proposeRevivalSupplyLanded(UUID.randomUUID(), round, human, 1));
        accept(engine, engine.proposeRevivalSupplyLanded(UUID.randomUUID(), round, human, 2));
        assertFalse(engine.committed().participants().get(human).witch().landingPending(2));
        assertEquals(160_000, engine.committed().participants().get(human).witch().blessingMillis(2), "Landing keeps the record");
        assertThrows(IllegalStateException.class, () -> engine.proposeRevivalSupplyLanded(UUID.randomUUID(), round, human, 2),
                "Delivered once");
    }

    @Test void aRevivalWithoutSuppliesOwesNothingAtLanding() {
        var engine = totemHolder(0);
        accept(engine, engine.proposeAdmissionRevival(UUID.randomUUID(), round, human, 1, 0));
        var revived = engine.committed().participants().get(human);
        assertFalse(revived.witch().landingPending(2));
        assertEquals(90_000, revived.witch().blessingMillis(2));
        assertThrows(IllegalStateException.class, () -> engine.proposeRevivalSupplyLanded(UUID.randomUUID(), round, human, 2));
    }

    @Test void theWitchGuideIsSeenOncePerRoundAcrossLives() {
        var engine = totemHolder(0);
        accept(engine, engine.proposeWitchGuideSeen(UUID.randomUUID(), round, human));
        assertThrows(IllegalStateException.class, () -> engine.proposeWitchGuideSeen(UUID.randomUUID(), round, human));
        assertThrows(IllegalStateException.class, () -> engine.proposeWitchGuideSeen(UUID.randomUUID(), UUID.randomUUID(), human));
        accept(engine, engine.proposeAdmissionRevival(UUID.randomUUID(), round, human, 1, 0));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(new RoundDeath(human, 2, 0, Optional.empty())), null));
        assertTrue(engine.committed().participants().get(human).witch().guideSeen(), "A second death does not repeat the guide");
    }

    @Test void theBlessingGrowsByStepsUpToFiveMinutes() {
        for (int bought = 0; bought <= 4; bought++) {
            var record = bought == 0 ? WitchRecord.NONE : WitchRecord.NONE.supplied(3, Map.of(ShopOffer.LONGER_BLESSING, bought));
            assertEquals(Math.min(300_000, 90_000 + 70_000L * bought), record.blessingMillis(3));
        }
        assertThrows(IllegalArgumentException.class, () -> WitchRecord.NONE.supplied(2, Map.of(ShopOffer.SWIFTNESS, 1)));
        assertThrows(IllegalArgumentException.class, () -> new WitchRecord(false, 0, Map.of(ShopOffer.BREAD, 1), false));
    }

    @Test void revivalSupplyLimitsCannotSellWhatIsNeverDelivered() {
        for (var key : List.of("coin.limit.shield", "coin.limit.bonusChest", "coin.limit.ironBucket", "coin.limit.ironPickaxe")) {
            var values = new java.util.HashMap<>(RULES.combatValues());
            values.put(key, 2.0);
            assertThrows(IllegalArgumentException.class, () -> withValues(values), key);
        }
        var values = new java.util.HashMap<>(RULES.combatValues());
        values.put("coin.limit.longerBlessing", 4.0);
        assertThrows(IllegalArgumentException.class, () -> withValues(values), "A fourth step would pass the cap");
        values.put("coin.limit.longerBlessing", 3.0);
        assertDoesNotThrow(() -> withValues(values), "Three steps reach five minutes exactly");
    }

    private static RulesConfig withValues(Map<String, Double> values) {
        return new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    @Test void deployingNeverCarriesRevivalSupplies() {
        var wallet = MonsterWallet.EMPTY.credit(100).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.BREAD, 30).chooseRole(RULES, MonsterRole.ZOMBIE);
        assertEquals(0, wallet.ordered(ShopOffer.BREAD), "A role choice never keeps goods no sortie can carry");
        assertEquals(100, wallet.coins());
        var kept = MonsterWallet.EMPTY.credit(100).chooseRole(RULES, MonsterRole.ZOMBIE).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.BREAD, 30)
                .deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(0, kept.carried(ShopOffer.BREAD));
        assertEquals(1, kept.ordered(ShopOffer.BREAD));
    }

    private Participant humanParticipant() {
        return new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false);
    }

    /** Camp monster at sortie 2; the engine fixture deploys it as sortie 3. */
    private Participant deployedMonster() {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of());
    }

    private RoundEngine engine(Participant... participants) {
        Map<UUID, Participant> players = new LinkedHashMap<>();
        for (var p : participants) players.put(p.id(), p);
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), clock);
        for (var p : participants) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        if (players.containsKey(monster)) accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        return engine;
    }

    private CombatOrigin origin(UUID owner, ParticipantRole role, int sortie, AttackKind kind) {
        return new CombatOrigin(round, UUID.randomUUID(), new CombatActor(owner, role, 1, sortie), kind, Optional.empty(), List.of());
    }

    private CombatEvent lethal(UUID victim, CombatOrigin origin) {
        var source = Optional.of(origin);
        return new CombatEvent(round, UUID.randomUUID(), UUID.randomUUID(), 1, victim, source,
                CombatEvent.classify(round, source), "test:lethal", Optional.empty(), Optional.empty(), 20, true, 0);
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
