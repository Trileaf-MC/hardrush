package dev.hardrush.core.round;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.config.RulesConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonsterPauseTest {
    private static final RulesConfig RULES = RulesConfig.defaults();
    private static final String BODY = "{form:\"ZOMBIE\"}";
    private final UUID round = UUID.randomUUID();
    private final UUID human = UUID.randomUUID();
    private final UUID monster = UUID.randomUUID();
    private final MonotonicClock clock = () -> 0;

    @Test void deliberateReturnPausesTheSortieWithoutCooldown() {
        var engine = engine(RULES, MonsterWallet.EMPTY.credit(40).chooseRole(RULES, MonsterRole.ZOMBIE)
                .buy(RULES, MonsterRole.ZOMBIE, ShopOffer.ADULT_EGG, 6));
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round,
                List.of(new RoundEngine.DamageCoins(monster, 1, 3, human, MonsterDimension.OVERWORLD, 20_000))));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, BODY));

        var paused = engine.view().participants().get(monster);
        assertEquals(ParticipantRole.MONSTER_LOBBY, paused.role());
        assertEquals(0, paused.monsterCooldownNanos());
        assertEquals(BODY, paused.wallet().parked());
        assertTrue(paused.wallet().active().isEmpty(), "The parked body holds what the sortie carried");
        assertEquals(1, paused.wallet().bought(ShopOffer.ADULT_EGG), "The price ladder continues through the pause");
        assertEquals(20_000, paused.wallet().sortieDamage().get(human).countedMilli());

        // Only goods bought during the pause are frozen for the resumed deployment.
        accept(engine, engine.proposeShopPurchase(UUID.randomUUID(), round, monster, 1, 3, ShopOffer.ADULT_EGG, 12));
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        var resumed = engine.view().participants().get(monster);
        assertEquals(1, resumed.wallet().carried(ShopOffer.ADULT_EGG));
        assertEquals(BODY, resumed.wallet().parked(), "The resumed deployment rebuilds from the parked body");
        assertEquals(20_000, resumed.wallet().sortieDamage().get(human).countedMilli(), "Damage caps span the whole sortie");

        // The cap still counts the damage dealt before the pause.
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round,
                List.of(new RoundEngine.DamageCoins(monster, 1, 4, human, MonsterDimension.OVERWORLD, 5_000))));
        assertEquals(20_000, engine.view().participants().get(monster).wallet().sortieDamage().get(human).countedMilli());

        accept(engine, engine.proposeMonsterDeath(UUID.randomUUID(), round, monster, 1, 4));
        var dead = engine.view().participants().get(monster);
        assertFalse(dead.wallet().hasParkedBody(), "Death ends the sortie");
        assertEquals(RoundEngine.MONSTER_COOLDOWN_NANOS, dead.monsterCooldownNanos());
        assertEquals(0, dead.wallet().bought(ShopOffer.ADULT_EGG));
    }

    @Test void choosingAnotherRoleAbandonsThePause() {
        var engine = engine(RULES, MonsterWallet.EMPTY.chooseRole(RULES, MonsterRole.ZOMBIE));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, BODY));
        accept(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, monster, 1, 3, MonsterRole.ZOMBIE));
        assertTrue(engine.view().participants().get(monster).wallet().hasParkedBody(), "The same role keeps its pause");
        accept(engine, engine.proposeMonsterRole(UUID.randomUUID(), round, monster, 1, 3, MonsterRole.CREEPER));
        assertFalse(engine.view().participants().get(monster).wallet().hasParkedBody());
    }

    @Test void deployCancellationKeepsThePausedSortie() {
        var engine = engine(RULES, MonsterWallet.EMPTY.chooseRole(RULES, MonsterRole.ZOMBIE));
        accept(engine, engine.proposeMonsterDamageCoins(UUID.randomUUID(), round,
                List.of(new RoundEngine.DamageCoins(monster, 1, 3, human, MonsterDimension.OVERWORLD, 3_000))));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, BODY));
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 3));
        accept(engine, engine.proposeMonsterDeployCancelled(UUID.randomUUID(), round, monster, 1, 4));
        var camp = engine.view().participants().get(monster).wallet();
        assertEquals(BODY, camp.parked());
        assertEquals(3_000, camp.sortieDamage().get(human).countedMilli());
    }

    @Test void returnWithoutBodyEndsTheSortieAndCooldownsFollowRules() {
        var values = new TreeMap<>(RULES.combatValues());
        values.put("monster.deployCooldownMillis", 12_000.0);
        values.put("monster.returnCooldownMillis", 4_000.0);
        var rules = rules(values);
        var engine = engine(rules, MonsterWallet.EMPTY.chooseRole(rules, MonsterRole.ZOMBIE));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3));
        var returned = engine.view().participants().get(monster);
        assertFalse(returned.wallet().hasParkedBody());
        assertEquals(4_000_000_000L, returned.monsterCooldownNanos());
        assertEquals(12_000_000_000L, RoundEngine.deployCooldownNanos(rules));

        values.remove("monster.returnCooldownMillis");
        assertEquals(12_000_000_000L, RoundEngine.returnCooldownNanos(rules(values)), "Older rounds keep one cooldown");
        assertThrows(IllegalArgumentException.class, () -> {
            var invalid = new TreeMap<>(RULES.combatValues());
            invalid.put("monster.deployCooldownMillis", 0.0);
            rules(invalid);
        });
    }

    @Test void parkedBodySurvivesTheSnapshotCodec() {
        var engine = engine(RULES, MonsterWallet.EMPTY.chooseRole(RULES, MonsterRole.ZOMBIE));
        accept(engine, engine.proposeMonsterReturn(UUID.randomUUID(), round, monster, 1, 3, BODY));
        var decoded = RoundSnapshotCodec.decode(RoundSnapshotCodec.encode(engine.committed()));
        assertEquals(BODY, decoded.participants().get(monster).wallet().parked());
        assertThrows(IllegalArgumentException.class, () -> MonsterWallet.EMPTY.park(""));
        assertThrows(IllegalArgumentException.class, () -> MonsterWallet.EMPTY.park("x".repeat(MonsterWallet.MAX_PARKED_TEXT + 1)));
    }

    private static RulesConfig rules(Map<String, Double> values) {
        return new RulesConfig(RULES.schemaVersion(), RULES.mode(), RULES.minecraftVersion(), RULES.difficulty(),
                RULES.generationRule(), values, RULES.startCountdownMillis(),
                RULES.successCelebrationMillis(), RULES.failureDisplayMillis());
    }

    /** A human and a monster deployed as sortie 3. */
    private RoundEngine engine(RulesConfig rules, MonsterWallet wallet) {
        Map<UUID, Participant> players = new LinkedHashMap<>();
        players.put(human, new Participant(human, "Human", ParticipantRole.HUMAN_ACTIVE, true, false, 100, 50, 1, false));
        players.put(monster, new Participant(monster, "Monster", ParticipantRole.MONSTER_LOBBY, true, false, 70, 20, 1, 0,
                MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 2, Map.of()).withWallet(wallet));
        var engine = RoundEngine.restore(new RoundSnapshot(round, 1, 2, 0, rules, RoundPhase.RUNNING, dev.hardrush.core.round.PauseReason.SERVER_STOPPED,
                100, 0, players, true, false, null), clock);
        for (var p : players.values()) accept(engine, engine.proposeOnline(UUID.randomUUID(), p.id(), p.name(), true));
        accept(engine, engine.proposeMonsterDeploy(UUID.randomUUID(), round, monster, 1, 2));
        return engine;
    }

    private static void accept(RoundEngine engine, RoundTransition transition) { assertTrue(engine.accept(transition)); }
}
