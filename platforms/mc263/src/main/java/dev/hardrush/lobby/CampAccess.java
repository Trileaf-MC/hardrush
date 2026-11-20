package dev.hardrush.lobby;

import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.round.RoundSession;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** What the camp's monster services (coins, kit, witch, vaults) read from the round runtime. */
interface CampAccess {
    Optional<RoundSnapshot> committed();
    /** A verified camp monster of the current hardcore round, outside any frozen state. */
    boolean eligible(ServerPlayer player);
    /** Personal deployment/revival work that must not race with a balance change. */
    boolean busy(UUID player);
    RoundSession session();
}
