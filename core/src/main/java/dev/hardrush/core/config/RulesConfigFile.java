package dev.hardrush.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Pending rules only; active rounds retain the immutable snapshot they already received. */
public final class RulesConfigFile {
    private RulesConfig pending;

    public RulesConfigFile(RulesConfig initial) { pending = Objects.requireNonNull(initial, "initial"); }

    public synchronized RulesConfig snapshotForNextRound() { return pending; }

    public synchronized void setPending(RulesConfig next) { pending = Objects.requireNonNull(next, "next"); }

    /** Invalid files leave the previous pending rules intact. File IO belongs off the server thread. */
    public void reload(Path path) throws IOException {
        RulesConfig validated = RulesConfig.read(path);
        setPending(validated);
    }
}
