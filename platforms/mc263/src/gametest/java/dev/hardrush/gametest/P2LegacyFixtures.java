package dev.hardrush.gametest;

import dev.hardrush.core.round.Participant;
import dev.hardrush.p2.protection.P2ProtectionRuntime;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/** Explicit test-artifact scope: legacy fixtures build/damage the lobby itself. */
public final class P2LegacyFixtures implements ModInitializer {
    @Override public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (Boolean.getBoolean("hardrush.p2.testIdentity")) return;
            // The dedicated P2 runtime test retains the production binding. Native protection
            // fixtures install their own zones; old P0/P1 fixtures predate protected lobbies.
            P2ProtectionRuntime.configure(server, new P2ProtectionRuntime.Access() {
                @Override public Optional<P2ProtectionRuntime.Zone> zone(ServerLevel level) { return Optional.empty(); }
                @Override public Optional<Participant> participant(UUID playerId) { return Optional.empty(); }
                @Override public boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence) { return false; }
            });
        });
    }
}
