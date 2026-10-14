package dev.hardrush.core.api;

import dev.hardrush.core.config.RulesConfig;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** The adapter performs budgeted world work on its supported threads and reports verified readiness. */
public interface WorldPort {
    enum Dimension { OVERWORLD, NETHER, END }

    record WorldSet(UUID roundId, long seed, Map<Dimension, String> worldKeys, WorldPosition safeSpawn) {
        public WorldSet {
            Objects.requireNonNull(roundId, "roundId");
            Objects.requireNonNull(worldKeys, "worldKeys");
            Objects.requireNonNull(safeSpawn, "safeSpawn");
            if (worldKeys.size() != Dimension.values().length) throw new IllegalArgumentException("Three round worlds required");
            var copy = new EnumMap<Dimension, String>(Dimension.class);
            for (Dimension dimension : Dimension.values()) {
                String key = worldKeys.get(dimension);
                if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing round world: " + dimension);
                copy.put(dimension, key);
            }
            if (copy.values().stream().distinct().count() != 3) throw new IllegalArgumentException("Round world keys must differ");
            if (!copy.get(Dimension.OVERWORLD).equals(safeSpawn.worldKey())) throw new IllegalArgumentException("Spawn must be in the round overworld");
            worldKeys = Collections.unmodifiableMap(copy);
        }
    }

    CompletionStage<OperationResult<WorldSet>> prepare(OperationTag tag, long seed, RulesConfig rules);

    /** Completes only after saving and releasing handles; this contract never authorizes deletion. */
    CompletionStage<OperationResult<WorldSet>> saveAndClose(OperationTag tag, WorldSet worlds);
}
