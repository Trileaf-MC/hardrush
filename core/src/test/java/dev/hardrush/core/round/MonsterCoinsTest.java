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

    @Test void pricesDoubleWithinOneSortieAndStopAtTheLimit() {
        var wallet = chosen(100);
        for (int price : new int[] {6, 12, 24}) {
            assertEquals(price, wallet.nextPrice(RULES, ShopOffer.ADULT_EGG));
            wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, price);
        }
        assertEquals(58, wallet.coins());
        assertEquals(3, wallet.ordered(ShopOffer.ADULT_EGG));
        assertTrue(wallet.soldOut(RULES, ShopOffer.ADULT_EGG));
        var full = wallet;
        assertThrows(IllegalStateException.class, () -> full.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 48));
        // The next sortie window starts again from the base price.
        var next = wallet.deploy(RULES, MonsterRole.ZOMBIE).endSortie();
        assertEquals(6, next.nextPrice(RULES, ShopOffer.ADULT_EGG));
    }

    @Test void purchasesRequireAChosenRoleASellableOfferTheShownPriceAndEnoughCoins() {
        var unchosen = MonsterWallet.EMPTY.credit(50);
        assertThrows(IllegalStateException.class, () -> unchosen.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6));
        var wallet = chosen(5);
        assertThrows(IllegalStateException.class, () -> wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6));
        assertThrows(IllegalStateException.class, () -> chosen(50).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.CHARGED, 40));
        assertThrows(IllegalStateException.class, () -> chosen(50).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 12));
    }

    @Test void changingRoleRefundsWhatTheNewRoleCannotUseAtThePricePaid() {
        var wallet = chosen(100)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 12)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.SWIFTNESS, 10)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 15);
        var skeleton = wallet.chooseRole(RULES, MonsterRole.SKELETON);
        assertEquals(100 - 10 - 15, skeleton.coins());
        assertEquals(0, skeleton.ordered(ShopOffer.ADULT_EGG));
        assertEquals(1, skeleton.ordered(ShopOffer.SWIFTNESS));
        assertEquals(1, skeleton.ordered(ShopOffer.TRIAL_KEY));
        var creeper = skeleton.chooseRole(RULES, MonsterRole.CREEPER);
        assertEquals(90, creeper.coins());
        assertEquals(15, creeper.nextPrice(RULES, ShopOffer.TRIAL_KEY));
    }

    @Test void deploymentFreezesTheKitRefundsUnopenedKeysAndCancellationRestoresIt() {
        var wallet = chosen(200)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.BABY_EGG, 16)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 15)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 30)
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.HEAD, Set.of(MonsterRole.ZOMBIE, MonsterRole.SKELETON), "helmet")
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.MAINHAND, Set.of(MonsterRole.SKELETON), "bow");
        assertEquals(200 - 16 - 15 - 30, wallet.coins());
        wallet = wallet.buy(RULES, MonsterRole.ZOMBIE, ShopOffer.OMINOUS_KEY, 40);
        var deployed = wallet.deploy(RULES, MonsterRole.ZOMBIE);
        assertEquals(139, deployed.coins(), "the unopened ominous key is refunded");
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
        accept(engine, engine.proposeHardcoreDeath(UUID.randomUUID(), human, 1));
        var wallet = engine.committed().participants().get(human).wallet();
        assertEquals(6, wallet.coins());
        assertFalse(wallet.roleChosen());
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, human, 1, 0, 1));
        accept(engine, engine.proposeAdmissionRevival(UUID.randomUUID(), round, human, 1, 0));
        var revived = engine.committed().participants().get(human);
        assertEquals(MonsterWallet.EMPTY, revived.wallet(), "Coins and paid goods never outlive the monster");
        assertEquals(0, revived.lifeHumanNanos(), "The revived life starts its survival time from zero");
        assertEquals(6, revived.wallet().becameMonster(6).coins(), "The next monster life gets the gift again");
    }

    @Test void aKillPaysARevivalAndSendsTheHunterBack() {
        var other = new Participant(UUID.randomUUID(), "Other", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false, true);
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
            assertEquals(5, victim.wallet().coins(), "Native player memory alone qualifies the deployed monster");
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

    @Test void bountyAddsDimensionAndSurvivalBonusesUnderTheCap() {
        long minute = 60_000_000_000L;
        assertEquals(1000, MonsterCoins.bountyPermille(RULES, MonsterDimension.OVERWORLD, 9 * minute + minute - 1));
        assertEquals(1250, MonsterCoins.bountyPermille(RULES, MonsterDimension.OVERWORLD, 10 * minute));
        assertEquals(1500, MonsterCoins.bountyPermille(RULES, MonsterDimension.NETHER, 0));
        assertEquals(2000, MonsterCoins.bountyPermille(RULES, MonsterDimension.NETHER, 30 * minute));
        assertEquals(2500, MonsterCoins.bountyPermille(RULES, MonsterDimension.END, 30 * minute));
        var values = new TreeMap<>(RULES.combatValues());
        values.put("coin.bounty.max", 1.75);
        var capped = new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.protectionRadius(), RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertEquals(1750, MonsterCoins.bountyPermille(capped, MonsterDimension.END, 30 * minute), "Bonuses add, then cap");
        assertEquals("1.25", MonsterCoins.multiplier(1250));
        assertEquals("2", MonsterCoins.multiplier(2000));
    }

    @Test void survivalTimeCountsOnlyTheCurrentOnlineLife() {
        var clock = new long[] {0};
        Map<UUID, Participant> players = new LinkedHashMap<>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 5_000, 50, 2, 0, true,
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
                new RoundEngine.DamageCoins(monster, 1, 3, human, MonsterDimension.NETHER, 4_000),
                new RoundEngine.DamageCoins(monster, 1, 2, human, MonsterDimension.OVERWORLD, 9_000),
                new RoundEngine.DamageCoins(UUID.randomUUID(), 1, 3, human, MonsterDimension.OVERWORLD, 9_000))));
        assertEquals(6, engine.committed().participants().get(monster).wallet().coins());
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), UUID.randomUUID(), List.of(
                new RoundEngine.DamageCoins(monster, 1, 3, human, MonsterDimension.OVERWORLD, 9_000))));
        assertEquals(6, engine.committed().participants().get(monster).wallet().coins());
    }

    @Test void snapshotsRoundTripTheWallet() {
        var wallet = chosen(80).buy(RULES, MonsterRole.ZOMBIE, ShopOffer.TRIAL_KEY, 15)
                .openVault(ShopOffer.TRIAL_KEY, MonsterWallet.GearSlot.CHEST, Set.of(MonsterRole.ZOMBIE), "{id:\"chest\"}")
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6)
                .creditDamage(human, 1_234, 1500, 20_000);
        var player = deployedMonster().withWallet(wallet.deploy(RULES, MonsterRole.ZOMBIE));
        var snapshot = new RoundSnapshot(round, 1, 2, 3, RULES, RoundPhase.RUNNING, PauseReason.NONE, 0, 0,
                Map.of(monster, player), false, false, null);
        assertEquals(snapshot, RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(snapshot)));
    }

    @Test void pricesMustFitOneEmeraldStackAfterDoubling() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put("coin.price.babyEgg", 40.0);
        assertThrows(IllegalArgumentException.class, () -> new RulesConfig(RULES.schemaVersion(), RULES.mode(),
                RULES.minecraftVersion(), RULES.difficulty(), RULES.generationRule(), values, RULES.protectionRadius(),
                RULES.startCountdownMillis(), RULES.successCelebrationMillis(), RULES.failureDisplayMillis()));
    }

    @Test void roundsWithoutCoinRulesKeepTheEconomyDisabled() {
        var values = new TreeMap<>(RULES.combatValues());
        values.keySet().removeIf(key -> key.startsWith("coin."));
        var legacy = new RulesConfig(RULES.schemaVersion(), GameMode.HARDCORE, RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.protectionRadius(), RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
        assertFalse(MonsterCoins.enabled(legacy));
        assertFalse(MonsterCoins.enabled(RULES.withMode(GameMode.NORMAL)));
        assertTrue(MonsterCoins.enabled(legacy.forNewRound()));
    }

    private Participant humanParticipant() {
        return new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false, true);
    }

    /** Camp monster at sortie 2; the engine fixture deploys it as sortie 3. */
    private Participant deployedMonster() {
        return new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0, true,
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
