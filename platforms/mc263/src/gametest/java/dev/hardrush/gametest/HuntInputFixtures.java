package dev.hardrush.gametest;

import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.LobbyProjections;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.lobby.LobbyRuntime;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Test-only migration: actual block/entity input through the production guards, never identity writes. */
final class HuntInputFixtures {
    /** A role model ({@code zombie}, {@code skeleton}, {@code creeper}) or a paging block ({@code previous}, {@code next}). */
    static void role(ServerPlayer player, String role) {
        var server = player.level().getServer();
        var function = switch (role) {
            case "zombie" -> LobbyFunction.ROLE_ZOMBIE; case "skeleton" -> LobbyFunction.ROLE_SKELETON;
            case "creeper" -> LobbyFunction.ROLE_CREEPER;
            case "previous" -> LobbyFunction.PAGE_PREV; case "next" -> LobbyFunction.PAGE_NEXT;
            default -> throw new IllegalArgumentException(role);
        };
        if (!LobbyPlanFixtures.plan(server).models(function).isEmpty()) {
            move(player, LobbyPlanFixtures.model(server, function).add(0, 0, 2));
            LobbyInteractions.tick(server);
            var ids = LobbyInteractions.get(server).orElseThrow().roleModels().view(player);
            click(player, ids.get(function).getFirst(), InteractionHand.MAIN_HAND);
            return;
        }
        var pos = LobbyPlanFixtures.block(server, function);
        move(player, LobbyPlanFixtures.before(pos));
        player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), 100));
    }
    /** Walks up to {@code target}'s hunt model as the client sees it, from the spawn packet the client received. */
    static LobbyProjections.Selection model(TestClient client, UUID target) {
        var player = client.player();
        LobbyRuntime.tick(player.level().getServer());
        var projections = LobbyInteractions.get(player.level().getServer()).orElseThrow().projections();
        var selection = projections.view(player).orElseThrow().entities().stream().filter(s -> s.target().equals(target)).findFirst().orElseThrow();
        if (spawn(client, selection.entityId()) == null) {
            // Dedicated crash fixtures discard acknowledged outbound buffers. Request a fresh native display,
            // then observe its NEW spawn packet; never invent coordinates or call the hunt business directly.
            projections.remove(player); LobbyRuntime.tick(player.level().getServer());
            selection = projections.view(player).orElseThrow().entities().stream().filter(s -> s.target().equals(target)).findFirst().orElseThrow();
        }
        var spawn = spawn(client, selection.entityId());
        if (spawn == null) throw new IllegalStateException("The client never received the hunt model of " + target);
        move(player, new Vec3(spawn.getX(), spawn.getY(), spawn.getZ() - 2));
        return selection;
    }
    static void hunt(TestClient client, UUID target) {
        click(client.player(), model(client, target).entityId(), InteractionHand.MAIN_HAND);
    }
    /** Left-click: the native attack packet a vanilla client sends for a pickable model. */
    static void watch(TestClient client, UUID target) {
        client.player().connection.handleAttack(new ServerboundAttackPacket(model(client, target).entityId()));
    }
    private static ClientboundAddEntityPacket spawn(TestClient client, int entity) {
        return client.packets(ClientboundAddEntityPacket.class).stream().filter(add -> add.getId() == entity).findFirst().orElse(null);
    }
    static void click(ServerPlayer player, int entity, InteractionHand hand) {
        var bytes = io.netty.buffer.Unpooled.buffer();
        try {
            ServerboundInteractPacket.STREAM_CODEC.encode(bytes, new ServerboundInteractPacket(entity, hand, new Vec3(0,1,0), false));
            player.connection.handleInteract(ServerboundInteractPacket.STREAM_CODEC.decode(bytes));
        } finally { bytes.release(); }
    }
    static void move(ServerPlayer player, Vec3 pos) {
        player.teleportTo(LobbyWorld.open(player.level().getServer()).holdingLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
    }
    private HuntInputFixtures() {}
}
