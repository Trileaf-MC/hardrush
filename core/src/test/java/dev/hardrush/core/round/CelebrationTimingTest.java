package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CelebrationTimingTest {
    private static final UUID HUMAN = UUID.randomUUID();
    private static final RoundResult COMPLETED = new RoundResult(RoundResult.Kind.COMPLETED, HUMAN, "verified first dragon");

    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
        void millis(long value) { now += value * 1_000_000; }
    }

    @Test void dragonAndGenericCompletionShareTheCapWithoutRewritingConfiguredRules() {
        for (long[] duration : new long[][]{{30_000, 30_000}, {90_000, 90_000}, {180_000, 180_000}, {600_000, 180_000}}) {
            var rules = configured(duration[0]);
            for (boolean dragon : List.of(false, true)) {
                var clock = new Clock();
                var engine = running(rules, clock);
                finish(engine, dragon);
                assertEquals(duration[1], engine.view().remainingPhaseMillis(), "dragon=" + dragon + ", configured=" + duration[0]);
                assertEquals(rules, engine.view().rules());
                assertEquals(rules.fingerprint(), engine.view().rules().fingerprint());
                clock.millis(duration[1]);
                apply(engine, engine.proposeTick(UUID.randomUUID()));
                assertEquals(RoundPhase.ROTATING, engine.view().phase());
            }
        }
    }

    @Test void defaultCelebrationExpiresAfterNinetyRealSecondsRegardlessOfTickFrequency() {
        for (boolean dragon : List.of(false, true)) {
            var clock = new Clock();
            var engine = running(RulesConfig.defaults(), clock);
            clock.millis(7_654);
            finish(engine, dragon);
            apply(engine, engine.proposeOnline(UUID.randomUUID(), HUMAN, "Human", false));
            // No intervening ticks: elapsed real time still consumes the timer even with no online humans.
            clock.millis(89_999);
            assertEquals(1, engine.view().remainingPhaseMillis());
            apply(engine, engine.proposeTick(UUID.randomUUID()));
            assertEquals(RoundPhase.CELEBRATING, engine.view().phase());
            clock.millis(1);
            apply(engine, engine.proposeTick(UUID.randomUUID()));
            assertEquals(RoundPhase.ROTATING, engine.view().phase());
            assertEquals(7_654, engine.view().challengeMillis());
            assertEquals(COMPLETED, engine.view().result());
        }
    }

    @Test void delayedDragonResultCommitConsumesTheOriginalNinetySecondWindow() {
        var clock = new Clock();
        var engine = running(RulesConfig.defaults(), clock);
        clock.millis(7_654);
        var terminal = engine.proposeDeathBatch(UUID.randomUUID(), engine.committed().roundId(), List.of(), COMPLETED);
        clock.millis(12_000);
        apply(engine, terminal);
        assertEquals(78_000, engine.view().remainingPhaseMillis());
        clock.millis(78_000);
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        assertEquals(RoundPhase.ROTATING, engine.view().phase());
        assertEquals(7_654, engine.view().challengeMillis());
    }

    private static void finish(RoundEngine engine, boolean dragon) {
        apply(engine, dragon
                ? engine.proposeDeathBatch(UUID.randomUUID(), engine.committed().roundId(), List.of(), COMPLETED)
                : engine.proposeFinish(UUID.randomUUID(), COMPLETED));
    }

    private static RoundEngine running(RulesConfig rules, Clock clock) {
        var engine = RoundEngine.create(UUID.randomUUID(), 1, 123, rules.withMode(GameMode.NORMAL), clock);
        apply(engine, engine.proposeWorldReady(UUID.randomUUID()));
        apply(engine, engine.proposeJoin(UUID.randomUUID(), HUMAN, "Human"));
        apply(engine, engine.proposeReady(UUID.randomUUID(), HUMAN, true));
        clock.millis(rules.startCountdownMillis());
        apply(engine, engine.proposeTick(UUID.randomUUID()));
        return engine;
    }

    private static RulesConfig configured(long millis) {
        var defaults = RulesConfig.defaults().withMode(GameMode.NORMAL);
        return new RulesConfig(defaults.schemaVersion(), defaults.mode(), defaults.minecraftVersion(), defaults.difficulty(),
                defaults.generationRule(), defaults.combatValues(), defaults.protectionRadius(),
                defaults.startCountdownMillis(), millis, defaults.failureDisplayMillis());
    }

    private static void apply(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
