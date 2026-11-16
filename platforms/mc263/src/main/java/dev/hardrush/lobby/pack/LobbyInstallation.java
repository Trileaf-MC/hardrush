package dev.hardrush.lobby.pack;

import dev.hardrush.core.lobby.plan.LobbyPlan;
import dev.hardrush.core.lobby.plan.PackManifest;
import java.util.List;
import java.util.Objects;

/**
 * What {@code hardrush/lobby/current.json} records about the installed lobby (design 10.1): the install sequence, its
 * dimension, the pack it came from and the effective plan. The plan is taken from here on restart; the pack is not
 * read again.
 */
public record LobbyInstallation(int seq, String dimension, Pack pack, LobbyPlan plan, State state) {
    /** Only complete installs are ever written; an unknown state is a broken file. */
    public enum State { COMPLETE }

    public record Pack(String id, String name, int version, List<String> authors, String sha256) {
        public Pack { authors = List.copyOf(authors); }
        public static Pack of(PackManifest manifest, String sha256) {
            return new Pack(manifest.id(), manifest.name(), manifest.version(), manifest.authors(), sha256);
        }
    }

    public LobbyInstallation {
        Objects.requireNonNull(dimension); Objects.requireNonNull(pack); Objects.requireNonNull(plan); Objects.requireNonNull(state);
    }
}
