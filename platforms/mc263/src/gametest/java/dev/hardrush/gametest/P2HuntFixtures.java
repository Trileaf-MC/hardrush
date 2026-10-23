package dev.hardrush.gametest;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.p1.lobby.LobbyInteractions;
import dev.hardrush.p1.lobby.LobbyProjections;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Test-only migration: actual block/entity input through the production guards, never identity writes. */
final class P2HuntFixtures {
    static void role(ServerPlayer player, String role) {
        var facility = LobbyLayout.Facility.valueOf(role.toUpperCase(java.util.Locale.ROOT));
        var lobby = P1LobbyWorld.open(player.level().getServer());
        var pos = BlockPos.containing(lobby.layoutPosition(LobbyLayout.facility(facility)));
        move(player, Vec3.atBottomCenterOf(pos.south(2)));
        if (LobbyLayout.MONSTER_FACILITIES.contains(facility)) {
            LobbyInteractions.tick(player.level().getServer());
            var ids = LobbyInteractions.get(player.level().getServer()).orElseThrow().roleModels().view(player);
            click(player, ids.get(facility), InteractionHand.MAIN_HAND);
            return;
        }
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 100));
    }
    static LobbyProjections.Selection model(ServerPlayer player, UUID target, List<Packet<?>> packets) {
        P2LobbyRuntime.tick(player.level().getServer());
        var projections = LobbyInteractions.get(player.level().getServer()).orElseThrow().projections();
        var selection = projections.view(player).orElseThrow().entities().stream().filter(s -> s.target().equals(target)).findFirst().orElseThrow();
        int entity = selection.entityId();
        if (packets.stream().noneMatch(packet -> packet instanceof ClientboundAddEntityPacket add && add.getId() == entity)) {
            // Dedicated crash fixtures discard acknowledged outbound buffers. Request a fresh native display,
            // then observe its NEW spawn packet; never invent coordinates or call the hunt business directly.
            projections.remove(player); P2LobbyRuntime.tick(player.level().getServer());
            selection = projections.view(player).orElseThrow().entities().stream().filter(s -> s.target().equals(target)).findFirst().orElseThrow();
        }
        int observed = selection.entityId();
        var spawn = packets.stream().filter(ClientboundAddEntityPacket.class::isInstance).map(ClientboundAddEntityPacket.class::cast)
                .filter(p -> p.getId() == observed).findFirst().orElseThrow();
        move(player, new Vec3(spawn.getX(), spawn.getY(), spawn.getZ() - 2));
        return selection;
    }
    static void hunt(ServerPlayer player, UUID target, List<Packet<?>> packets) {
        click(player, model(player, target, packets).entityId(), InteractionHand.MAIN_HAND);
    }
    static void click(ServerPlayer player, int entity, InteractionHand hand) {
        var bytes = io.netty.buffer.Unpooled.buffer();
        try {
            ServerboundInteractPacket.STREAM_CODEC.encode(bytes, new ServerboundInteractPacket(entity, hand, new Vec3(0,1,0), false));
            player.connection.handleInteract(ServerboundInteractPacket.STREAM_CODEC.decode(bytes));
        } finally { bytes.release(); }
    }
    static void move(ServerPlayer player, Vec3 pos) {
        player.teleportTo(P1LobbyWorld.open(player.level().getServer()).holdingLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
    }
    private P2HuntFixtures() {}
}
