package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalHuntRequestTest {
    final UUID round = UUID.randomUUID(), hunter = UUID.randomUUID(), target = UUID.randomUUID(), second = UUID.randomUUID();
    Participant monster(UUID id) { return new Participant(id, "Hunter", ParticipantRole.MONSTER_LOBBY, true, false,
            0, 0, 3, false, true, MonsterRole.ZOMBIE, MonsterDimension.END, 0, 7); }
    Participant human() { return new Participant(target, "Target", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 4, false); }
    PersonalHuntRequest request(UUID owner) { return new PersonalHuntRequest(round, owner, 3, 7, MonsterRole.ZOMBIE, target, 4); }
    RoundSnapshot snapshot(Participant human) {
        var players = new LinkedHashMap<UUID, Participant>(); players.put(hunter, monster(hunter)); players.put(second, monster(second)); players.put(target, human);
        return new RoundSnapshot(round, 1, 99, 0, RulesConfig.defaults(), RoundPhase.RUNNING, PauseReason.NONE,
                0, 0, players, true, false, null);
    }
    RoundEngine engine(Participant human) {
        var e = RoundEngine.restore(snapshot(human), () -> 0);
        for (var id : new UUID[]{hunter, second}) assertTrue(e.accept(e.proposeOnline(UUID.randomUUID(), id, "Hunter", true)));
        if (human.online()) assertTrue(e.accept(e.proposeOnline(UUID.randomUUID(), target, human.name(), true)));
        return e;
    }
    @Test void exactLifeIsReservedDurablyAndTwoHuntersCanSelectSameHuman() {
        var e = engine(human());
        var first = e.proposePersonalHunt(UUID.randomUUID(), request(hunter), 7);
        assertEquals(ParticipantRole.MONSTER_LOBBY, e.committed().participants().get(hunter).role());
        assertTrue(e.accept(first));
        assertTrue(e.accept(e.proposePersonalHunt(UUID.randomUUID(), request(second), 7)));
        assertEquals(8, e.committed().participants().get(hunter).monsterDeploymentSequence());
        assertEquals(8, e.committed().participants().get(second).monsterDeploymentSequence());
        assertEquals(MonsterDimension.END, e.committed().participants().get(hunter).monsterDimension());
    }
    @Test void replacementLifeOfSameUuidCannotInheritOldRequest() {
        var newer = new Participant(target, "Renamed", ParticipantRole.HUMAN_ACTIVE, true, false, 0, 0, 5, false);
        assertFalse(request(hunter).targetMatches(snapshot(newer)));
        assertThrows(IllegalStateException.class, () -> engine(newer).proposePersonalHunt(UUID.randomUUID(), request(hunter), 7));
    }
    @Test void offlineAndDeadTargetsCancelWithoutSelectingAnotherHuman() {
        for (var p : new Participant[]{human().withSession("Away", false), human().withRole(ParticipantRole.MONSTER_LOBBY)}) {
            assertThrows(IllegalStateException.class, () -> engine(p).proposePersonalHunt(UUID.randomUUID(), request(hunter), 7));
        }
    }
    @Test void roundHunterLifeSortieAndRoleAreFrozen() {
        var s = snapshot(human());
        assertFalse(new PersonalHuntRequest(UUID.randomUUID(), hunter, 3, 7, MonsterRole.ZOMBIE, target, 4).targetMatches(s));
        assertFalse(new PersonalHuntRequest(round, hunter, 4, 7, MonsterRole.ZOMBIE, target, 4).hunterMatches(s, ParticipantRole.MONSTER_LOBBY, 7));
        assertFalse(new PersonalHuntRequest(round, hunter, 3, 7, MonsterRole.SKELETON, target, 4).hunterMatches(s, ParticipantRole.MONSTER_LOBBY, 7));
        assertFalse(request(hunter).hunterMatches(s, ParticipantRole.MONSTER_LOBBY, 8));
    }
    @Test void compensationRefundsOnlyExactSortieAndKeepsFrozenTarget() {
        var e = engine(human()); var q = request(hunter);
        assertTrue(e.accept(e.proposePersonalHunt(UUID.randomUUID(), q, 7)));
        assertTrue(e.accept(e.proposeMonsterDeployCancelled(UUID.randomUUID(), round, hunter, 3, 8)));
        assertTrue(e.accept(e.proposePersonalHunt(UUID.randomUUID(), q, 8)));
        assertThrows(IllegalStateException.class, () -> e.proposeMonsterDeployCancelled(UUID.randomUUID(), round, hunter, 3, 8));
        assertEquals(9, e.committed().participants().get(hunter).monsterDeploymentSequence());
    }
    @Test void roleChoicePreservesCompatibilityDimensionAndAllCounters() {
        var e = engine(human());
        assertTrue(e.accept(e.proposeMonsterRole(UUID.randomUUID(), round, hunter, 3, 7, MonsterRole.SKELETON)));
        var p = e.committed().participants().get(hunter);
        assertEquals(MonsterRole.SKELETON, p.monsterRole()); assertEquals(MonsterDimension.END, p.monsterDimension());
        assertEquals(7, p.monsterDeploymentSequence());
        assertThrows(IllegalStateException.class, () -> e.proposeMonsterSelection(UUID.randomUUID(), round, hunter, 3, 7, MonsterRole.ZOMBIE, MonsterDimension.NETHER));
    }
}
