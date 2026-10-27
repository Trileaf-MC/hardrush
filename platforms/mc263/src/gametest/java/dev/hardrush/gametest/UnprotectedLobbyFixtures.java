package dev.hardrush.gametest;

import dev.hardrush.core.round.Participant;
import dev.hardrush.protection.ProtectionRuntime;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/** Explicit test-artifact scope: legacy fixtures build/damage the lobby itself. */
public final class UnprotectedLobbyFixtures implements ModInitializer {
    @Override public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (Boolean.getBoolean("hardrush.lobby.testParticipationExit")) return;
            if (Boolean.getBoolean("hardrush.lobby.testAdminSpectator")) return;
            if (Boolean.getBoolean("hardrush.p2.testPersonalHunt") || Boolean.getBoolean("hardrush.lobby.testFoundation") || Boolean.getBoolean("hardrush.p2.testIdentity") || Boolean.getBoolean("hardrush.p2.testLobby")) return;
            // The dedicated P2 runtime test retains the production binding. Native protection
            // fixtures install their own zones; old P0/P1 fixtures predate protected lobbies.
            ProtectionRuntime.configure(server, new ProtectionRuntime.Access() {
                @Override public Optional<ProtectionRuntime.Zone> zone(ServerLevel level) { return Optional.empty(); }
                @Override public Optional<Participant> participant(UUID playerId) { return Optional.empty(); }
                @Override public boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence) { return false; }
            });
        });
    }
}
