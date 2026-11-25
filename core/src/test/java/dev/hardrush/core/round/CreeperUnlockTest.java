package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreeperUnlockTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private final UUID round = UUID.randomUUID();
    private final UUID hunter = UUID.randomUUID();
    private final UUID oneStar = UUID.randomUUID();
    private final UUID twoStars = UUID.randomUUID();
    private final UUID threeStars = UUID.randomUUID();

    @Test void selectingCreeperCostsFortyAndReselectingCostsNothing() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertEquals(10, hunter(engine).wallet().coins());
        assertTrue(hunter(engine).wallet().creeperTicket());
        assertTrue(hunter(engine).wallet().roleChosen());

        online(engine, threeStars, false);
        accept(engine, engine.proposeMonsterSelection(UUID.randomUUID(), round, hunter, 1, 0,
                MonsterRole.CREEPER, MonsterDimension.OVERWORLD));
        assertEquals(10, hunter(engine).wallet().coins());
    }

    @Test void insufficientCoinsRejectSelectionAndKeepTheParkedBody() {
        var zombie = camp(39);
        var engine = engine(RULES, zombie.withWallet(zombie.wallet().park("zombie-body")), true);
        var before = engine.committed();
        var error = assertThrows(Refusal.Exception.class, () -> engine.proposeMonsterSelection(UUID.randomUUID(), round,
                hunter, 1, 0, MonsterRole.CREEPER, MonsterDimension.OVERWORLD));
        assertEquals(Refusal.NOT_ENOUGH_COINS, error.refusal());
        assertTrue(error.getMessage().contains("需要 40，现有 39"));
        assertFalse(engine.pending());
        assertEquals(before, engine.committed());
    }

    @Test void onlyAnOnlineThreeStarHumanAllowsUnlocking() {
        var engine = engine(RULES, camp(50), false);
        assertEquals(3, standing(engine, threeStars).stars());
        assertEquals(2, standing(engine, twoStars).stars());
        var before = engine.committed();
        assertEquals(Refusal.CREEPER_NO_QUARRY, assertThrows(Refusal.Exception.class,
                () -> select(engine, MonsterRole.CREEPER)).refusal());
        assertFalse(engine.pending());
        assertEquals(before, engine.committed());

        online(engine, threeStars, true);
        accept(engine, engine.proposeMonsterSelection(UUID.randomUUID(), round, hunter, 1, 0,
                MonsterRole.CREEPER, MonsterDimension.OVERWORLD));
        assertTrue(hunter(engine).wallet().creeperTicket());
        assertEquals(10, hunter(engine).wallet().coins());
    }

    @Test void changingRoleDiscardsThePauseButKeepsTheCreeperTicket() {
        var engine = deployed();
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, "creeper-body"));
        select(engine, MonsterRole.ZOMBIE);
        assertFalse(hunter(engine).wallet().hasParkedBody());
        assertTrue(hunter(engine).wallet().creeperTicket());
        hunt(engine, oneStar);
        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 2));
        select(engine, MonsterRole.CREEPER);
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertTrue(hunter(engine).wallet().creeperTicket());
        assertEquals(10, hunter(engine).wallet().coins());
    }

    @Test void bothDeploymentEntriesRequireATicketEvenWhenTheQuarryHasAHuntLock() {
        var engine = engine(RULES, camp(50).selectMonsterRole(MonsterRole.CREEPER)
                .withHuntLock(threeStars, 1_000_000_000L), true);
        assertEquals(Refusal.CREEPER_LOCKED, huntRefusal(engine, threeStars));
        assertEquals(Refusal.CREEPER_LOCKED, assertThrows(Refusal.Exception.class,
                () -> engine.proposeMonsterDeploy(UUID.randomUUID(), round, hunter, 1, 0)).refusal());
        assertFalse(engine.pending());
    }

    @Test void lowerStarsAreRefusedBeforeTheHuntLockAndThreeStarsCanBeHunted() {
        assertEquals(3, HuntPressure.CREEPER_MIN_STARS);
        var engine = engine(RULES, camp(50).withHuntLock(oneStar, 1_000_000_000L), true);
        select(engine, MonsterRole.CREEPER);
        assertEquals(Refusal.CREEPER_LOW_STARS, huntRefusal(engine, oneStar));
        assertEquals(Refusal.CREEPER_LOW_STARS, huntRefusal(engine, twoStars));
        assertTrue(hunter(engine).wallet().creeperTicket());
        hunt(engine, threeStars);
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, hunter(engine).role());
        assertEquals(Sortie.onto(threeStars, 3), hunter(engine).sortie());
        assertTrue(hunter(engine).wallet().creeperTicket());
    }

    @Test void detonationInADeathBatchSpendsTheTicket() {
        var engine = deployed();
        var detonation = new RoundDeath.Detonation(Optional.of(threeStars), Optional.empty());
        var death = new RoundDeath(hunter, 1, 1, Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.of(detonation));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(death), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertFalse(hunter(engine).wallet().creeperTicket());
        assertTrue(hunter(engine).huntLockNanos(threeStars) > 0);
        assertEquals(Refusal.CREEPER_LOCKED, huntRefusal(engine, threeStars));
    }

    @Test void monsterDeathSpendsTheTicketButADisconnectKeepsIt() {
        var killed = deployed();
        accept(killed, killed.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(killed).role());
        assertFalse(hunter(killed).wallet().creeperTicket());

        // Also covers a disconnect racing a deployment that never landed: the ticket survives either way.
        var disconnected = deployed();
        online(disconnected, hunter, false);
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(disconnected).role());
        assertTrue(hunter(disconnected).wallet().creeperTicket());
    }

    @Test void deliberateReturnCanRedeployOnTheSameTicketWithOrWithoutAParkedBody() {
        for (String body : List.of("creeper-body", "")) {
            var engine = deployed();
            accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, body));
            assertEquals(body, hunter(engine).wallet().parked());
            assertTrue(hunter(engine).wallet().creeperTicket());
            hunt(engine, threeStars);
            assertEquals(2, hunter(engine).monsterDeploymentSequence());
            assertEquals(10, hunter(engine).wallet().coins());
            assertTrue(hunter(engine).wallet().creeperTicket());
        }
    }

    @Test void aChargeWaitsThroughRoleChangesUntilAPaidCreeperUsesIt() {
        var engine = engine(RULES, camp(100), true);
        select(engine, MonsterRole.CREEPER);
        buy(engine, ShopOffer.CHARGED);
        assertEquals(20, hunter(engine).wallet().coins());
        select(engine, MonsterRole.ZOMBIE);
        assertEquals(1, hunter(engine).wallet().ordered(ShopOffer.CHARGED), "Another role does not refund the charge");
        assertEquals(20, hunter(engine).wallet().coins());
        select(engine, MonsterRole.CREEPER);
        assertEquals(20, hunter(engine).wallet().coins(), "The ticket was kept too");
        hunt(engine, threeStars);
        assertEquals(1, hunter(engine).wallet().carried(ShopOffer.CHARGED));
        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(0, hunter(engine).wallet().ordered(ShopOffer.CHARGED), "A death uses the charge up");
        assertFalse(hunter(engine).wallet().creeperTicket());
    }

    @Test void aChargeCarriedOutComesBackWhenTheSortieEndsUnused() {
        for (String ending : List.of("return", "disconnect", "abandon")) {
            var engine = engine(RULES, camp(100), true);
            select(engine, MonsterRole.CREEPER);
            buy(engine, ShopOffer.CHARGED);
            hunt(engine, threeStars);
            switch (ending) {
                case "return" -> accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, ""));
                case "disconnect" -> online(engine, hunter, false);
                default -> {
                    accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, "creeper-body"));
                    assertEquals(1, hunter(engine).wallet().parkedGoods().get(ShopOffer.CHARGED), "The parked body holds the charge");
                    if (!hunter(engine).online()) online(engine, hunter, true);
                    select(engine, MonsterRole.ZOMBIE);
                    assertTrue(hunter(engine).wallet().parked().isEmpty());
                }
            }
            var wallet = hunter(engine).wallet();
            assertEquals(1, wallet.ordered(ShopOffer.CHARGED), ending + " keeps the charge for the next creeper sortie");
            assertTrue(wallet.parkedGoods().isEmpty());
            assertTrue(wallet.creeperTicket());
        }
    }

    @Test void deploymentCancelledBeforeArrivalKeepsTheTicket() {
        var engine = deployed();
        accept(engine, engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).wallet().creeperTicket());
        hunt(engine, threeStars);
        assertEquals(10, hunter(engine).wallet().coins());
    }

    @Test void revivalRecallSpendsADeployedCreepersTicket() {
        var engine = deployed();
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, hunter, 1, 0, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).revivalEntitled());
        assertFalse(hunter(engine).wallet().creeperTicket());
    }

    @Test void earningARevivalFromAKillAlsoRecallsTheCreeperAndSpendsItsTicket() {
        var engine = deployed();
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter,
                ParticipantRole.MONSTER_DEPLOYED, 1, 1), AttackKind.CREEPER, Optional.empty(), List.of());
        var death = new RoundDeath(threeStars, 1, 0, Optional.empty(), Optional.of(origin), false);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(death), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).revivalEntitled());
        assertFalse(hunter(engine).wallet().creeperTicket());
    }

    @Test void aRevivedHumanAndANewMonsterLifeHaveNoTicket() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        var paid = hunter(engine).wallet();
        assertFalse(paid.becameMonster(6).creeperTicket());
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, hunter, 1, 0, 1));
        assertTrue(hunter(engine).wallet().creeperTicket());
        accept(engine, engine.proposeRevival(UUID.randomUUID(), round, hunter, 1, 0));
        assertEquals(MonsterWallet.EMPTY, hunter(engine).wallet());
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(hunter, 2, 0, Optional.empty())), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertFalse(hunter(engine).wallet().creeperTicket());
        assertEquals(6, hunter(engine).wallet().coins());
    }

    @Test void oldRulesAndRulesWithoutBountiesKeepCreepersFreeAndUnrestricted() {
        var old = RulesConfig.decode(RULES.encode().replace("combat.coin.creeperUnlock=40.0\n", ""));
        var noBounties = RulesConfig.decode(RULES.encode().replaceAll("(?m)^combat\\.bounty\\..*\n", ""));
        assertFalse(old.combatValues().containsKey(MonsterCoins.CREEPER_UNLOCK));
        assertFalse(MonsterCoins.creeperPaid(RULES.withMode(GameMode.NORMAL)));
        for (var rules : List.of(old, noBounties)) {
            assertFalse(MonsterCoins.creeperPaid(rules));
            var engine = engine(rules, camp(0), false);
            select(engine, MonsterRole.CREEPER);
            assertEquals(0, hunter(engine).wallet().coins());
            assertFalse(hunter(engine).wallet().creeperTicket());
            hunt(engine, oneStar);
            assertEquals(Sortie.onto(oneStar, 1), hunter(engine).sortie());
        }
    }

    private RoundEngine deployed() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        hunt(engine, threeStars);
        return engine;
    }

    private RoundEngine engine() { return engine(RULES, camp(50), true); }

    private RoundEngine engine(RulesConfig rules, Participant monster, boolean strongOnline) {
        var players = Map.of(hunter, monster, oneStar, human(oneStar, 0), twoStars, human(twoStars, 4),
                threeStars, human(threeStars, 9));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, rules, RoundPhase.RUNNING,
                PauseReason.SERVER_STOPPED, 0, 0, players, true, false, null), () -> 0);
        for (UUID id : List.of(oneStar, twoStars, hunter)) online(engine, id, true);
        if (strongOnline) online(engine, threeStars, true);
        return engine;
    }

    private Participant camp(int coins) {
        return new Participant(hunter, "Hunter", ParticipantRole.MONSTER_LOBBY, false, false, 0, 0, 1, false)
                .withWallet(MonsterWallet.EMPTY.credit(coins));
    }

    private static Participant human(UUID id, int kills) {
        return new Participant(id, "Human", ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, false)
                .withThreat(new HumanThreat(1, kills, Set.of()));
    }

    private static Bounty.Standing standing(RoundEngine engine, UUID id) {
        var snapshot = engine.committed();
        return Bounty.standing(snapshot.rules(), snapshot.participants(), id);
    }

    private Participant hunter(RoundEngine engine) { return engine.committed().participants().get(hunter); }

    private static void online(RoundEngine engine, UUID id, boolean online) {
        accept(engine, engine.proposeOnline(UUID.randomUUID(), id, engine.committed().participants().get(id).name(), online));
    }

    private void select(RoundEngine engine, MonsterRole role) {
        var player = hunter(engine);
        accept(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, hunter, player.lifeSequence(),
                player.monsterDeploymentSequence(), role));
    }

    private void buy(RoundEngine engine, ShopOffer offer) {
        var player = hunter(engine);
        accept(engine, engine.proposeShopPurchase(UUID.randomUUID(), round, hunter, player.lifeSequence(),
                player.monsterDeploymentSequence(), offer, player.wallet().nextPrice(RULES, offer)));
    }

    private Refusal huntRefusal(RoundEngine engine, UUID target) {
        return assertThrows(Refusal.Exception.class, () -> hunt(engine, target)).refusal();
    }

    private void hunt(RoundEngine engine, UUID target) {
        var player = hunter(engine);
        int sortie = player.monsterDeploymentSequence();
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, hunter, player.lifeSequence(), sortie, player.monsterRole(), target, 1), sortie));
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
