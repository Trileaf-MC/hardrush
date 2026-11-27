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

    @Test void selectingCreeperCostsThirtyFiveAndReselectingCostsNothing() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertEquals(15, hunter(engine).wallet().coins());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        assertTrue(hunter(engine).wallet().roleChosen());

        online(engine, threeStars, false);
        accept(engine, engine.proposeMonsterSelection(UUID.randomUUID(), round, hunter, 1, 0,
                MonsterRole.CREEPER, MonsterDimension.OVERWORLD));
        assertEquals(15, hunter(engine).wallet().coins());
    }

    @Test void insufficientCoinsRejectSelectionAndKeepTheParkedBody() {
        var zombie = camp(34);
        var engine = engine(RULES, zombie.withWallet(zombie.wallet().park("zombie-body")), true);
        var before = engine.committed();
        var error = assertThrows(Refusal.Exception.class, () -> engine.proposeMonsterSelection(UUID.randomUUID(), round,
                hunter, 1, 0, MonsterRole.CREEPER, MonsterDimension.OVERWORLD));
        assertEquals(Refusal.NOT_ENOUGH_COINS, error.refusal());
        assertTrue(error.getMessage().contains("需要 35，现有 34"));
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
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        assertEquals(15, hunter(engine).wallet().coins());
    }

    @Test void changingRoleDiscardsThePauseButKeepsTheCreeperTicket() {
        var engine = deployed();
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, "creeper-body"));
        select(engine, MonsterRole.ZOMBIE);
        assertFalse(hunter(engine).wallet().hasParkedBody());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        hunt(engine, oneStar);
        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 2));
        select(engine, MonsterRole.CREEPER);
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        assertEquals(15, hunter(engine).wallet().coins());
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
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        hunt(engine, threeStars);
        assertEquals(ParticipantRole.MONSTER_DEPLOYED, hunter(engine).role());
        assertEquals(Sortie.onto(threeStars, 3).landed(), hunter(engine).sortie());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
    }

    @Test void detonationInADeathBatchSpendsTheTicket() {
        var engine = deployed();
        var detonation = new RoundDeath.Detonation(Optional.of(threeStars), Optional.empty());
        var death = new RoundDeath(hunter, 1, 1, Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.of(detonation));
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(death), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertFalse(hunter(engine).wallet().creeperTicket().unlocked());
        assertTrue(hunter(engine).huntLockNanos(threeStars) > 0);
        assertEquals(Refusal.CREEPER_LOCKED, huntRefusal(engine, threeStars));
    }

    @Test void monsterDeathSpendsTheTicketButADisconnectKeepsIt() {
        var killed = deployed();
        accept(killed, killed.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(killed).role());
        assertFalse(hunter(killed).wallet().creeperTicket().unlocked());

        // Also covers a disconnect racing a deployment that never landed: the ticket survives either way.
        var disconnected = deployed();
        online(disconnected, hunter, false);
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(disconnected).role());
        assertTrue(hunter(disconnected).wallet().creeperTicket().unlocked());
    }

    @Test void deliberateReturnCanRedeployOnTheSameTicketWithOrWithoutAParkedBody() {
        for (String body : List.of("creeper-body", "")) {
            var engine = deployed();
            accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, body));
            assertEquals(body, hunter(engine).wallet().parked());
            assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
            hunt(engine, threeStars);
            assertEquals(2, hunter(engine).monsterDeploymentSequence());
            assertEquals(15, hunter(engine).wallet().coins());
            assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        }
    }

    @Test void chargingNeedsAnUnlockedTicketWhichItUpgradesOnceForCoins() {
        var none = MonsterWallet.EMPTY.credit(100);
        assertEquals("请先解锁苦力怕。", assertThrows(IllegalStateException.class, () -> none.chargeCreeper(RULES, 40)).getMessage());
        var poor = MonsterWallet.EMPTY.credit(74).unlockCreeper(35);
        var refusal = assertThrows(Refusal.Exception.class, () -> poor.chargeCreeper(RULES, 40));
        assertEquals(Refusal.NOT_ENOUGH_COINS, refusal.refusal());
        assertTrue(refusal.getMessage().contains("需要 40，现有 39"));
        var charged = MonsterWallet.EMPTY.credit(100).unlockCreeper(35).chargeCreeper(RULES, 40);
        assertEquals(CreeperTicket.CHARGED, charged.creeperTicket());
        assertEquals(25, charged.coins());
        assertEquals("苦力怕已经带电。", assertThrows(IllegalStateException.class, () -> charged.chargeCreeper(RULES, 40)).getMessage());
        assertEquals(0, charged.sortieInvestment(), "a charge bought in the camp counts when the creeper sets out");
        assertTrue(charged.creeperTicket().unlocked());
        assertTrue(charged.creeperTicket().charged());
        assertFalse(CreeperTicket.PLAIN.charged());
        assertFalse(CreeperTicket.NONE.unlocked());
    }

    @Test void lightningChargesOnlyAPlainTicketAndCostsNothing() {
        var plain = MonsterWallet.EMPTY.credit(50).unlockCreeper(35);
        var struck = plain.struckByLightning();
        assertEquals(CreeperTicket.CHARGED, struck.creeperTicket());
        assertEquals(15, struck.coins());
        assertSame(struck, struck.struckByLightning());
        var none = MonsterWallet.EMPTY.credit(50);
        assertSame(none, none.struckByLightning());
        assertEquals(CreeperTicket.NONE, struck.useCreeperTicket().creeperTicket());
    }

    @Test void theBoothChargesACampCreeperAtTheShownPrice() {
        var engine = engine(RULES, camp(100), true);
        assertEquals("请先选择苦力怕。", assertThrows(IllegalStateException.class, () -> charge(engine, 40)).getMessage());
        select(engine, MonsterRole.CREEPER);
        assertEquals(65, hunter(engine).wallet().coins());
        var before = engine.committed();
        assertEquals("价格已变化，请重新右键苦力怕展位。",
                assertThrows(IllegalStateException.class, () -> charge(engine, 39)).getMessage());
        assertFalse(engine.pending());
        assertEquals(before, engine.committed());
        accept(engine, charge(engine, MonsterCoins.creeperChargePrice(RULES)));
        assertEquals(CreeperTicket.CHARGED, hunter(engine).wallet().creeperTicket());
        assertEquals(25, hunter(engine).wallet().coins());
        assertThrows(IllegalStateException.class, () -> charge(engine, 40));
        // Out on a sortie the booth is out of reach.
        hunt(engine, threeStars);
        assertThrows(IllegalStateException.class, () -> charge(engine, 40));
    }

    @Test void aSpentTicketMustBeUnlockedAgainBeforeItCanBeCharged() {
        var engine = deployed();
        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(MonsterRole.CREEPER, hunter(engine).monsterRole());
        assertEquals("请先解锁苦力怕。", assertThrows(IllegalStateException.class, () -> charge(engine, 40)).getMessage());
    }

    @Test void roundsWithoutABoothChargeCannotChargeACreeper() {
        var old = RulesConfig.decode(RULES.encode().replace("combat.coin.creeperCharge=40.0\n", ""));
        assertTrue(MonsterCoins.creeperPaid(old));
        assertFalse(MonsterCoins.creeperChargeable(old));
        assertTrue(MonsterCoins.creeperChargeable(RULES));
        var engine = engine(old, camp(100), true);
        select(engine, MonsterRole.CREEPER);
        assertThrows(IllegalStateException.class, () -> charge(engine, 40));
    }

    @Test void aChargedTicketSurvivesEveryEndingThatKeepsTheTicket() {
        for (String ending : List.of("return", "park", "cancel", "disconnect", "role")) {
            var engine = engine(RULES, camp(100), true);
            select(engine, MonsterRole.CREEPER);
            accept(engine, charge(engine, 40));
            if (ending.equals("cancel")) commitHunt(engine, threeStars); else hunt(engine, threeStars);
            switch (ending) {
                case "return" -> accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, ""));
                case "park" -> accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, "creeper-body"));
                case "cancel" -> accept(engine, engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, hunter, 1, 1));
                case "disconnect" -> { online(engine, hunter, false); online(engine, hunter, true); }
                default -> {
                    accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, "creeper-body"));
                    select(engine, MonsterRole.ZOMBIE);
                    assertFalse(hunter(engine).wallet().hasParkedBody());
                }
            }
            assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role(), ending);
            assertEquals(CreeperTicket.CHARGED, hunter(engine).wallet().creeperTicket(), ending + " keeps the charge");
            select(engine, MonsterRole.CREEPER);
            assertEquals(25, hunter(engine).wallet().coins(), ending + " needs no second unlock or charge");
        }
    }

    @Test void deathSpendsAChargedTicket() {
        var engine = engine(RULES, camp(100), true);
        select(engine, MonsterRole.CREEPER);
        accept(engine, charge(engine, 40));
        hunt(engine, threeStars);
        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(CreeperTicket.NONE, hunter(engine).wallet().creeperTicket());
    }

    @Test void lightningChargesADeployedPaidCreeperOnceForFree() {
        var engine = deployed();
        assertThrows(IllegalStateException.class, () -> engine.proposeCreeperStruck(UUID.randomUUID(), round, hunter, 1, 0),
                "an earlier sortie's strike is stale");
        accept(engine, engine.proposeCreeperStruck(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(CreeperTicket.CHARGED, hunter(engine).wallet().creeperTicket());
        assertEquals(15, hunter(engine).wallet().coins());
        assertThrows(IllegalStateException.class, () -> engine.proposeCreeperStruck(UUID.randomUUID(), round, hunter, 1, 1),
                "an already charged creeper");
        // The strike's charge stays with the ticket through a deliberate return.
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, hunter, 1, 1, ""));
        assertEquals(CreeperTicket.CHARGED, hunter(engine).wallet().creeperTicket());
        assertThrows(IllegalStateException.class, () -> engine.proposeCreeperStruck(UUID.randomUUID(), round, hunter, 1, 1),
                "a camp creeper");

        var zombie = engine(RULES, camp(50), true);
        select(zombie, MonsterRole.ZOMBIE);
        hunt(zombie, threeStars);
        assertThrows(IllegalStateException.class, () -> zombie.proposeCreeperStruck(UUID.randomUUID(), round, hunter, 1, 1));
    }

    private RoundTransition charge(RoundEngine engine, int price) {
        var player = hunter(engine);
        return engine.proposeCreeperCharge(UUID.randomUUID(), round, hunter, player.lifeSequence(),
                player.monsterDeploymentSequence(), price);
    }

    @Test void deploymentCancelledBeforeArrivalKeepsTheTicket() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        commitHunt(engine, threeStars);
        accept(engine, engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, hunter, 1, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        hunt(engine, threeStars);
        assertEquals(15, hunter(engine).wallet().coins());
    }

    @Test void aDisconnectBeforeLandingKeepsTheTicketWithNoWait() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        commitHunt(engine, threeStars);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), hunter, "Hunter", false));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        assertEquals(0, hunter(engine).monsterCooldownNanos());
    }

    @Test void revivalRecallSpendsADeployedCreepersTicket() {
        var engine = deployed();
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, hunter, 1, 0, 1));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).revivalEntitled());
        assertFalse(hunter(engine).wallet().creeperTicket().unlocked());
    }

    @Test void earningARevivalFromAKillAlsoRecallsTheCreeperAndSpendsItsTicket() {
        var engine = deployed();
        var origin = new CombatOrigin(round, UUID.randomUUID(), new CombatActor(hunter,
                ParticipantRole.MONSTER_DEPLOYED, 1, 1), AttackKind.CREEPER, Optional.empty(), List.of());
        var death = new RoundDeath(threeStars, 1, 0, Optional.empty(), Optional.of(origin), false);
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round, List.of(death), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertTrue(hunter(engine).revivalEntitled());
        assertFalse(hunter(engine).wallet().creeperTicket().unlocked());
    }

    @Test void aRevivedHumanAndANewMonsterLifeHaveNoTicket() {
        var engine = engine();
        select(engine, MonsterRole.CREEPER);
        var paid = hunter(engine).wallet();
        assertFalse(paid.becameMonster(6).creeperTicket().unlocked());
        accept(engine, engine.proposeAdminGrantRevival(UUID.randomUUID(), round, hunter, 1, 0, 1));
        assertTrue(hunter(engine).wallet().creeperTicket().unlocked());
        accept(engine, engine.proposeRevival(UUID.randomUUID(), round, hunter, 1, 0));
        assertEquals(MonsterWallet.EMPTY, hunter(engine).wallet());
        accept(engine, engine.proposeDeathBatch(UUID.randomUUID(), round,
                List.of(new RoundDeath(hunter, 2, 0, Optional.empty())), null));
        assertEquals(ParticipantRole.MONSTER_LOBBY, hunter(engine).role());
        assertFalse(hunter(engine).wallet().creeperTicket().unlocked());
        assertEquals(6, hunter(engine).wallet().coins());
    }

    @Test void oldRulesAndRulesWithoutBountiesKeepCreepersFreeAndUnrestricted() {
        var old = RulesConfig.decode(RULES.encode().replace("combat.coin.creeperUnlock=35.0\n", ""));
        var noBounties = RulesConfig.decode(RULES.encode().replaceAll("(?m)^combat\\.bounty\\..*\n", ""));
        assertFalse(old.combatValues().containsKey(MonsterCoins.CREEPER_UNLOCK));
        assertFalse(MonsterCoins.creeperPaid(RULES.withMode(GameMode.NORMAL)));
        for (var rules : List.of(old, noBounties)) {
            assertFalse(MonsterCoins.creeperPaid(rules));
            var engine = engine(rules, camp(0), false);
            select(engine, MonsterRole.CREEPER);
            assertEquals(0, hunter(engine).wallet().coins());
            assertFalse(hunter(engine).wallet().creeperTicket().unlocked());
            hunt(engine, oneStar);
            assertEquals(Sortie.onto(oneStar, 1).landed(), hunter(engine).sortie());
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

    private Refusal huntRefusal(RoundEngine engine, UUID target) {
        return assertThrows(Refusal.Exception.class, () -> hunt(engine, target)).refusal();
    }

    /** Commits the hunt and lands it, as the platform does once the body reaches its drop point. */
    private void hunt(RoundEngine engine, UUID target) {
        commitHunt(engine, target);
        var player = hunter(engine);
        accept(engine, engine.proposeMonsterLanded(UUID.randomUUID(), round, hunter, player.lifeSequence(), player.monsterDeploymentSequence()));
    }

    private void commitHunt(RoundEngine engine, UUID target) {
        var player = hunter(engine);
        int sortie = player.monsterDeploymentSequence();
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, hunter, player.lifeSequence(), sortie, player.monsterRole(), target, 1), sortie));
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
