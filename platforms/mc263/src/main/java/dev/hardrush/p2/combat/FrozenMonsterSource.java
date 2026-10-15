package dev.hardrush.p2.combat;

import java.util.Optional;
import java.util.UUID;

/** Creation identity survives the owner's later death, logout or return to a human life. */
public record FrozenMonsterSource(UUID roundId, UUID ownerId, int life, int deployment, boolean admitted) {
    public String encode() { return roundId + ":" + ownerId + ":" + life + ":" + deployment + ":" + admitted; }
    public static Optional<FrozenMonsterSource> decode(String encoded) {
        try {
            var fields = encoded.split(":", -1);
            if (fields.length != 5 || !fields[4].equals("true") && !fields[4].equals("false")) return Optional.empty();
            int life = Integer.parseInt(fields[2]), deployment = Integer.parseInt(fields[3]);
            if (life < 0 || deployment < 0) return Optional.empty();
            return Optional.of(new FrozenMonsterSource(UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                    life, deployment, Boolean.parseBoolean(fields[4])));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
