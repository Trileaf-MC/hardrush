package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LateShelterTest {
    static final long MINUTE = 60_000_000_000L;
    final RulesConfig hardcore = RulesConfig.defaults().withMode(GameMode.HARDCORE);

    static Participant human(long joinedAt, int life, long lifeHuman) {
        return new Participant(UUID.randomUUID(), "Late", ParticipantRole.HUMAN_ACTIVE, true, false, lifeHuman, joinedAt, life, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, lifeHuman);
    }

    @Test void defaultsToFiveMinutesOfTheFirstLifesOwnHumanTime() {
        assertEquals(5 * MINUTE, LateShelter.durationNanos(hardcore));
        assertEquals(5, LateShelter.minutes(hardcore));
        assertEquals(5 * MINUTE, LateShelter.remainingNanos(hardcore, human(1, 1, 0)));
        assertEquals(2 * MINUTE, LateShelter.remainingNanos(hardcore, human(1, 1, 3 * MINUTE)));
        assertFalse(LateShelter.sheltered(hardcore, human(1, 1, 5 * MINUTE)));
    }

    @Test void openingEntrantsLaterLivesAndOtherRolesAreNeverSheltered() {
        assertFalse(human(0, 1, 0).lateEntrant());
        assertTrue(human(1, 1, 0).lateEntrant());
        assertFalse(LateShelter.sheltered(hardcore, human(0, 1, 0)), "The dusk pact covers opening entrants");
        assertFalse(LateShelter.sheltered(hardcore, human(1, 2, 0)), "An altar revival is a new life, blessed instead");
        assertFalse(LateShelter.sheltered(hardcore, human(1, 1, 0).withRole(ParticipantRole.MONSTER_LOBBY)));
        assertFalse(LateShelter.sheltered(hardcore, null));
    }

    @Test void onlyHardcorePersonalHuntsHaveIt() {
        assertFalse(LateShelter.sheltered(RulesConfig.defaults().withMode(GameMode.NORMAL), human(1, 1, 0)));
        assertFalse(LateShelter.sheltered(hardcore.withHuntRule(RulesConfig.HuntRule.LEGACY_VOTE), human(1, 1, 0)));
    }

    @Test void theDurationIsARuleAndOlderRulesWithoutTheKeyKeepTheDefault() {
        var values = new TreeMap<>(hardcore.combatValues());
        values.put(LateShelter.KEY, 90_000.0);
        var custom = new RulesConfig(hardcore.schemaVersion(), hardcore.mode(), hardcore.minecraftVersion(), hardcore.difficulty(),
                hardcore.generationRule(), values, hardcore.startCountdownMillis(), hardcore.successCelebrationMillis(), hardcore.failureDisplayMillis());
        assertEquals(90_000_000_000L, LateShelter.durationNanos(custom));
        assertEquals(2, LateShelter.minutes(custom));
        values.remove(LateShelter.KEY);
        var older = new RulesConfig(hardcore.schemaVersion(), hardcore.mode(), hardcore.minecraftVersion(), hardcore.difficulty(),
                hardcore.generationRule(), values, hardcore.startCountdownMillis(), hardcore.successCelebrationMillis(), hardcore.failureDisplayMillis());
        assertEquals(5 * MINUTE, LateShelter.durationNanos(older));
    }

    @Test void clockRoundsUpToTheNextSecond() {
        assertEquals("5:00", LateShelter.clock(5 * MINUTE));
        assertEquals("4:32", LateShelter.clock(271_500_000_000L));
        assertEquals("0:01", LateShelter.clock(1));
        assertEquals("0:00", LateShelter.clock(0));
    }
}
