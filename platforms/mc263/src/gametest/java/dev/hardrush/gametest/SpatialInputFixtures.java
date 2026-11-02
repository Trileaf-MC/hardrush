package dev.hardrush.gametest;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.world.LobbyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Migration of historical fixture commands to actual spatial/menu inputs. Not a production API. */
final class SpatialInputFixtures {
    /** Quarantine intentionally keeps the circle inert; standing in it never starts admission. */
    static void closedAssembly(ServerPlayer player) {
        var lobby = LobbyWorld.open(player.level().getServer());
        var marker = BlockPos.containing(lobby.holdingSpawn()).offset(6, 3, 6);
        if (!lobby.ready() || lobby.builtBlocks() != 0
                || !lobby.holdingLevel().getBlockState(marker).is(net.minecraft.world.level.block.Blocks.LAPIS_BLOCK))
            throw new AssertionError("Recovered shared camp was rebuilt or lost its native terrain sentinel");
        var origin = player.position();
        standInAssembly(player);
        if (dev.hardrush.lobby.HumanEntry.guiding(player)) throw new AssertionError("Quarantine started mid-round admission");
        move(player, origin);
    }
    /** Stand in the circle and run one entry tick so its effect is observable synchronously. */
    static void standInAssembly(ServerPlayer player) {
        move(player, LobbyWorld.open(player.level().getServer()).landing(LobbyLayout.Landing.RESTORE_READY));
        dev.hardrush.lobby.HumanEntry.tick(player.level().getServer());
    }
    /** The historical revive command: stand on the altar's north-west gold cell; the rite submits after the portal delay. */
    static void standOnAltar(ServerPlayer player) {
        var lobby = LobbyWorld.open(player.level().getServer());
        move(player, lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.ALTAR)).add(-1, 1, -1));
    }
    static boolean handle(ServerPlayer player, String command) {
        if (command.equals("hardrush revive")) { standOnAltar(player); return true; }
        if (!command.equals("hardrush join") && !command.equals("hardrush ready") && !command.equals("hardrush unready")) return false;
        var server = player.level().getServer();
        var round = RoundRuntime.snapshot(server).orElseThrow(); var lobby = LobbyWorld.open(server);
        if (command.equals("hardrush unready")) { move(player, lobby.landing(LobbyLayout.Landing.WAIT_OUTSIDE)); return true; }
        if (round.phase() == RoundPhase.WAITING || round.phase() == RoundPhase.COUNTDOWN) {
            move(player, lobby.landing(LobbyLayout.Landing.RESTORE_READY)); return true;
        }
        if (command.equals("hardrush ready")) return true;
        // Mid-round: stand in the circle; admission follows after the portal delay.
        move(player, lobby.landing(LobbyLayout.Landing.RESTORE_READY));
        return true;
    }
    private static void move(ServerPlayer player, Vec3 pos) {
        player.teleportTo(LobbyWorld.open(player.level().getServer()).holdingLevel(), pos.x, pos.y, pos.z, java.util.Set.of(), 0, 0, true);
        // The enclosing fixture acknowledges actual outbound teleport IDs each tick.
    }
    private SpatialInputFixtures() {}
}
