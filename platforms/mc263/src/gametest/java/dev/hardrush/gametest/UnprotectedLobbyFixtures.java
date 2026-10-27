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
            if (Boolean.getBoolean(TestProperties.PARTICIPATION_EXIT)) return;
            if (Boolean.getBoolean(TestProperties.ADMIN_SPECTATOR)) return;
            if (Boolean.getBoolean(TestProperties.PERSONAL_HUNT) || Boolean.getBoolean(TestProperties.LOBBY_FOUNDATION) || Boolean.getBoolean(TestProperties.IDENTITY_RUNTIME) || Boolean.getBoolean(TestProperties.LOBBY_RUNTIME)) return;
            // The dedicated lobby runtime test retains the production binding. Native protection
            // fixtures install their own zones; unmanaged fixtures do not participate in the protected lobby lifecycle.
            ProtectionRuntime.configure(server, new ProtectionRuntime.Access() {
                @Override public Optional<ProtectionRuntime.Zone> zone(ServerLevel level) { return Optional.empty(); }
                @Override public Optional<Participant> participant(UUID playerId) { return Optional.empty(); }
                @Override public boolean leaveProtection(UUID roundId, UUID playerId, int lifeSequence) { return false; }
            });
        });
    }
}
