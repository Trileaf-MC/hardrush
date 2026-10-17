package dev.hardrush.gametest;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.p1.P1LobbyRuntime;
import dev.hardrush.p1.world.P1LobbyWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Migration of historical fixture commands to actual spatial/menu inputs. Not a production API. */
final class P1SpatialFixtures {
    static boolean handle(ServerPlayer player, String command) {
        if (!command.equals("hardrush join") && !command.equals("hardrush ready") && !command.equals("hardrush unready")) return false;
        var server = player.level().getServer();
        var round = P1LobbyRuntime.snapshot(server).orElseThrow(); var lobby = P1LobbyWorld.open(server);
        if (command.equals("hardrush unready")) { move(player, lobby.landing(LobbyLayout.Landing.WAIT_OUTSIDE)); return true; }
        if (round.phase() == RoundPhase.WAITING || round.phase() == RoundPhase.COUNTDOWN) {
            move(player, lobby.landing(LobbyLayout.Landing.RESTORE_READY)); return true;
        }
        if (command.equals("hardrush ready")) return true;
        var pos = BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(LobbyLayout.Facility.MIDROUND)));
        move(player, Vec3.atBottomCenterOf(pos.south(2)));
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 90));
        var menu = player.containerMenu;
        if (!dev.hardrush.p2.lobby.P2LobbyUi.isMenu(player)) throw new IllegalStateException("Fixture's physical midround gate did not open");
        player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short)2, (byte)0,
                ContainerInput.PICKUP, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
        return true;
    }
    private static void move(ServerPlayer player, Vec3 pos) {
        player.teleportTo(P1LobbyWorld.open(player.level().getServer()).holdingLevel(), pos.x, pos.y, pos.z, java.util.Set.of(), 0, 0, true);
        // The enclosing fixture acknowledges actual outbound teleport IDs each tick.
    }
    private P1SpatialFixtures() {}
}
