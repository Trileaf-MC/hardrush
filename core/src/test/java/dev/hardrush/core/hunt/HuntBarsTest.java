package dev.hardrush.core.hunt;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.hunt.HuntBars.HumanView.Candidate;
import dev.hardrush.core.hunt.HuntBars.Settings;
import dev.hardrush.core.hunt.HuntBars.TargetState;
import dev.hardrush.core.round.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HuntBarsTest {
    final Settings settings = Settings.DEFAULTS;
    final UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3), d = new UUID(0, 4);

    static Candidate hunter(UUID id, double distance) { return new Candidate(id, distance, 1.0, true); }
    static Candidate passer(UUID id, double distance) { return new Candidate(id, distance, 1.0, false); }

    @Test void defaultsPutSneakingAppearanceAtAboutFourBlocks() {
        assertEquals(5, settings.showDistance());
        assertEquals(8, settings.hideDistance());
        assertFalse(settings.darken());
        assertEquals(4.0, settings.showDistance() * 0.8, 1e-9);
    }

    @Test void parseKeepsDefaultsForMissingKeysAndRejectsInvalidSets() {
        var values = Map.of("showDistance", "6", "darken", "TRUE");
        var parsed = Settings.parse(values::get);
        assertEquals(new Settings(6, 8, true, 3), parsed);
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(Map.of("hideDistance", "4")::get));
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(Map.of("showDistance", "abc")::get));
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(Map.of("darken", "yes")::get));
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(Map.of("darkenDistance", "0")::get));
        assertThrows(IllegalArgumentException.class, () -> Settings.parse(Map.of("hideDistance", "NaN")::get));
    }

    @Test void proximityUsesHysteresisThenLingersThreeSeconds() {
        var view = new HuntBars.HumanView();
        assertEquals(List.of(), view.update(List.of(hunter(a, 6)), settings, 0).monsters());
        assertEquals(List.of(a), view.update(List.of(hunter(a, 5)), settings, 100).monsters());
        // Between the two thresholds the bar stays.
        assertEquals(List.of(a), view.update(List.of(hunter(a, 7.9)), settings, 5_000).monsters());
        // Leaving the hide distance starts the linger from the last in-range update.
        assertEquals(List.of(a), view.update(List.of(hunter(a, 8.1)), settings, 6_000).monsters());
        assertEquals(List.of(a), view.update(List.of(hunter(a, 30)), settings, 7_999).monsters());
        assertEquals(List.of(), view.update(List.of(hunter(a, 30)), settings, 8_000).monsters());
        // Re-entering the buffer zone alone does not bring it back.
        assertEquals(List.of(), view.update(List.of(hunter(a, 7)), settings, 9_000).monsters());
    }

    @Test void sneakingAndInvisibilityShrinkTheRadiusLikeVanillaDetection() {
        var view = new HuntBars.HumanView();
        var sneaking = new Candidate(a, 4.5, 0.8, true);
        assertEquals(List.of(), view.update(List.of(sneaking), settings, 0).monsters());
        assertEquals(List.of(a), view.update(List.of(new Candidate(a, 4.0, 0.8, true)), settings, 50).monsters());
        var invisible = new Candidate(b, 1.0, 0.07, true);
        assertEquals(List.of(a), view.update(List.of(new Candidate(a, 4.0, 0.8, true), invisible), settings, 100).monsters());
    }

    @Test void onlyTheViewersHuntersTriggerByProximity() {
        var view = new HuntBars.HumanView();
        assertEquals(List.of(a), view.update(List.of(hunter(a, 4), passer(b, 1)), settings, 0).monsters());
    }

    @Test void damageFromAnyMonsterShowsRegardlessOfDistanceAndRefreshesLinger() {
        var view = new HuntBars.HumanView();
        view.damaged(b, 1_000);
        assertEquals(List.of(b), view.update(List.of(passer(b, 40)), settings, 1_000).monsters());
        view.damaged(b, 3_500);
        assertEquals(List.of(b), view.update(List.of(passer(b, 40)), settings, 6_499).monsters());
        assertEquals(List.of(), view.update(List.of(passer(b, 40)), settings, 6_500).monsters());
    }

    @Test void fallenAttackerOnlyKeepsTheRemainingDamageLinger() {
        var view = new HuntBars.HumanView();
        // The adapter reports a body that died in the same blast as a non-hunting candidate.
        view.damaged(a, 1_000);
        assertEquals(List.of(a), view.update(List.of(passer(a, 2)), settings, 1_200).monsters());
        assertEquals(List.of(a), view.update(List.of(passer(a, 2)), settings, 3_999).monsters());
        assertEquals(List.of(), view.update(List.of(passer(a, 2)), settings, 4_000).monsters());
        // A proximity-only hunter that falls is not kept longer than its existing linger either.
        assertEquals(List.of(b), view.update(List.of(hunter(b, 3)), settings, 5_000).monsters());
        assertEquals(List.of(b), view.update(List.of(passer(b, 3)), settings, 7_999).monsters());
        assertEquals(List.of(), view.update(List.of(passer(b, 3)), settings, 8_000).monsters());
    }

    @Test void damageFromAnInvalidOrOtherDimensionMonsterIsDropped() {
        var view = new HuntBars.HumanView();
        view.damaged(a, 0);
        assertEquals(List.of(), view.update(List.of(), settings, 10).monsters());
        assertEquals(List.of(), view.update(List.of(passer(a, 3)), settings, 20).monsters());
        view.damaged(b, 0);
        assertEquals(List.of(), view.update(List.of(passer(b, Double.NaN)), settings, 10).monsters());
        assertEquals(List.of(), view.update(List.of(passer(b, 3)), settings, 20).monsters());
    }

    @Test void atMostThreeBarsSortedByDistance() {
        var view = new HuntBars.HumanView();
        var frame = view.update(List.of(hunter(a, 4), hunter(b, 1), hunter(c, 3), hunter(d, 2)), settings, 0);
        assertEquals(List.of(b, d, c), frame.monsters());
    }

    @Test void darkenOnlyWhenEnabledAndWithinScaledDistance() {
        var view = new HuntBars.HumanView();
        assertFalse(view.update(List.of(hunter(a, 1)), settings, 0).darken());
        var on = new Settings(5, 8, true, 3);
        assertTrue(view.update(List.of(hunter(a, 3)), on, 10).darken());
        assertFalse(view.update(List.of(hunter(a, 4)), on, 20).darken());
        assertFalse(view.update(List.of(new Candidate(a, 2.5, 0.8, true)), on, 30).darken());
    }

    Participant human(int life, boolean online) {
        return new Participant(a, "Alex", ParticipantRole.HUMAN_ACTIVE, online, false, 0, 0, life, 0, true,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of());
    }
    Participant as(Participant p, ParticipantRole role) {
        return new Participant(p.id(), p.name(), role, p.online(), false, 0, 0, p.lifeSequence(), 0, true,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of());
    }

    @Test void targetStateFollowsTheSelectedLife() {
        assertEquals(TargetState.HUNTING, HuntBars.targetState(human(2, true), 2, true));
        assertEquals(TargetState.OTHER_DIMENSION, HuntBars.targetState(human(2, true), 2, false));
        assertEquals(TargetState.OFFLINE, HuntBars.targetState(human(2, false), 2, false));
        assertEquals(TargetState.KILLED, HuntBars.targetState(as(human(2, true), ParticipantRole.MONSTER_LOBBY), 2, true));
        assertEquals(TargetState.KILLED, HuntBars.targetState(human(3, true), 2, true));
        assertEquals(TargetState.GONE, HuntBars.targetState(as(human(2, true), ParticipantRole.WITHDRAWN), 2, true));
        assertEquals(TargetState.GONE, HuntBars.targetState(null, 2, true));
    }

    @Test void killIsAnnouncedOnceThenHiddenForTheSortie() {
        var view = new HuntBars.TargetView();
        assertTrue(view.visible(TargetState.HUNTING, 0));
        assertTrue(view.visible(TargetState.OFFLINE, 10));
        assertTrue(view.visible(TargetState.KILLED, 1_000));
        assertTrue(view.visible(TargetState.KILLED, 3_999));
        assertFalse(view.visible(TargetState.KILLED, 4_000));
        assertFalse(view.visible(TargetState.HUNTING, 5_000));
        assertFalse(new HuntBars.TargetView().visible(TargetState.GONE, 0));
    }

    @Test void titles() {
        assertEquals("来自 Steve 的追猎", HuntBars.humanTitle("Steve"));
        assertEquals("追猎目标 · Alex", HuntBars.targetTitle(TargetState.HUNTING, "Alex"));
        assertEquals("目标已被击毙", HuntBars.targetTitle(TargetState.KILLED, "Alex"));
        assertEquals("目标离线", HuntBars.targetTitle(TargetState.OFFLINE, "Alex"));
        assertEquals("目标已不在当前维度", HuntBars.targetTitle(TargetState.OTHER_DIMENSION, "Alex"));
    }
}
