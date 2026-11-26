package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.PersonalHuntRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A personal hunt is committed before its body lands; ending it in between cancels it rather than spending it. */
class SortieLandingTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private static final String BODY = "{form:\"ZOMBIE\"}";
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final MonotonicClock clock = () -> 0;

    @Test void aCommittedHuntIsLandingUntilThePlatformPutsItDown() {
        var engine = engine();
        hunt(engine, 2);
        assertTrue(monster(engine).sortie().landing());
        land(engine, 3);
        assertFalse(monster(engine).sortie().landing());
        assertEquals(human, monster(engine).quarry().orElseThrow());
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterLanded(UUID.randomUUID(), round, monster, 1, 3));
        assertThrows(IllegalStateException.class, () -> engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, monster, 1, 3),
                "A landed sortie has been used; it can no longer be cancelled");
    }

    @Test void aDisconnectBeforeLandingCancelsTheSortie() {
        var engine = engine();
        hunt(engine, 2);
        assertEquals(1, monster(engine).wallet().carried(ShopOffer.ADULT_EGG));
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", false));
        var camp = monster(engine);
        assertEquals(ParticipantRole.MONSTER_LOBBY, camp.role());
        assertFalse(camp.online());
        assertEquals(0, camp.monsterCooldownNanos(), "No wait for an outing that never opened");
        assertEquals(1, camp.wallet().ordered(ShopOffer.ADULT_EGG), "The frozen egg is back in the camp");
        assertEquals(0, camp.wallet().carried(ShopOffer.ADULT_EGG));
        assertEquals(1, camp.wallet().bought(ShopOffer.ADULT_EGG), "Its paid ladder step stays with it");
        assertEquals(3, camp.monsterDeploymentSequence());
        assertEquals(Sortie.NONE, camp.sortie());
    }

    @Test void aDisconnectAfterLandingEndsTheSortie() {
        var engine = engine();
        hunt(engine, 2);
        land(engine, 3);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", false));
        var camp = monster(engine);
        assertEquals(ParticipantRole.MONSTER_LOBBY, camp.role());
        assertEquals(RoundEngine.deployCooldownNanos(RULES), camp.monsterCooldownNanos());
        assertEquals(0, camp.wallet().ordered(ShopOffer.ADULT_EGG), "The landed body carried the egg away");
        assertEquals(0, camp.wallet().bought(ShopOffer.ADULT_EGG));
    }

    @Test void aRestartBeforeLandingCancelsTheSortieOnReconnect() {
        var engine = engine();
        hunt(engine, 2);
        accept(engine, engine.proposeCheckpoint(UUID.randomUUID(), true));
        var stored = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        assertTrue(stored.participants().get(monster).sortie().landing(), "Landing survives the snapshot");
        var restarted = RoundEngine.restore(stored, clock);
        accept(restarted, restarted.proposeOnline(UUID.randomUUID(), monster, "Monster", true));
        var camp = monster(restarted);
        assertEquals(ParticipantRole.MONSTER_LOBBY, camp.role());
        assertEquals(0, camp.monsterCooldownNanos());
        assertEquals(1, camp.wallet().ordered(ShopOffer.ADULT_EGG));
    }

    @Test void aResumedPauseThatNeverLandsStaysPaused() {
        var engine = engine();
        hunt(engine, 2);
        land(engine, 3);
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, BODY));
        hunt(engine, 3);
        accept(engine, engine.proposeOnline(UUID.randomUUID(), monster, "Monster", false));
        var camp = monster(engine);
        assertEquals(BODY, camp.wallet().parked(), "The parked body waits for the next deployment");
        assertEquals(0, camp.monsterCooldownNanos());
        assertEquals(new Sortie(java.util.Optional.empty(), 1), camp.sortie(), "The pause keeps its tier");
    }

    private void hunt(RoundEngine engine, int sortie) {
        accept(engine, engine.proposePersonalHunt(UUID.randomUUID(),
                new PersonalHuntRequest(round, monster, 1, sortie, MonsterRole.ZOMBIE, human, 1), sortie));
    }

    private void land(RoundEngine engine, int sortie) {
        accept(engine, engine.proposeMonsterLanded(UUID.randomUUID(), round, monster, 1, sortie));
    }

    private Participant monster(RoundEngine engine) { return engine.committed().participants().get(monster); }

    /** A human and a zombie in the camp before its third sortie, an adult egg bought for it. */
    private RoundEngine engine() {
        var wallet = MonsterWallet.EMPTY.credit(40).chooseRole(RULES, MonsterRole.ZOMBIE)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6);
        Map<UUID, Participant> players = new LinkedHashMap<>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, false, HumanThreat.NONE));
        players.put(monster, new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of()).withWallet(wallet));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, RULES, RoundPhase.RUNNING, PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), clock);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
