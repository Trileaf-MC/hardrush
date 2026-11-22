package dev.hardrush.hunt;

import dev.hardrush.monster.MonsterMining;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Burrows: when no walkable landing turns up, a monster is hidden in a pocket hollowed out of the plain ground beside
 * its quarry's tunnel and breaks out by digging, the way a silverfish bursts from infested stone. The pocket is found by
 * {@link SafeDeployment}; this hollows it at departure and tells whether its monster still lies hidden there. Nothing is
 * ever restored, so nothing is stored.
 */
public final class HuntBurrows {
    private record Hidden(ServerLevel level, BlockPos pocket) {}
    private static final Map<MinecraftServer, Map<UUID, Hidden>> HIDDEN = new HashMap<>();

    private HuntBurrows() {}

    /** Only where monsters may change the ground, since only digging leads out again. */
    public static boolean allowed(ServerLevel level) { return MonsterMining.griefing(level); }

    /**
     * Hollows the pocket whose floor center is {@code point}, without drops, and shows it to the hunter first so it
     * never sees itself inside stone for a frame. One silverfish rustle sounds from the stone.
     */
    public static void carve(ServerLevel level, Vec3 point, ServerPlayer hunter) {
        var pocket = BlockPos.containing(point);
        for (var cell : new BlockPos[]{pocket, pocket.above()}) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (hunter.level() == level) hunter.connection.send(new ClientboundBlockUpdatePacket(level, cell));
        }
        level.playSound(null, point.x, point.y + 1, point.z, SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 1, 1);
    }

    /** The hunter has landed in its pocket. */
    public static void entered(ServerPlayer hunter, Vec3 point) {
        HIDDEN.computeIfAbsent(hunter.level().getServer(), ignored -> new HashMap<>())
                .put(hunter.getUUID(), new Hidden(hunter.level(), BlockPos.containing(point)));
    }

    /**
     * Still standing in its sealed pocket: no side at foot or head height opened yet. Once out, or once opened by
     * anyone, it is an ordinary monster again for good.
     */
    public static boolean hidden(ServerPlayer hunter) {
        var hidden = HIDDEN.get(hunter.level().getServer());
        var burrow = hidden == null ? null : hidden.get(hunter.getUUID());
        if (burrow == null) return false;
        boolean sealed = burrow.level() == hunter.level() && hunter.blockPosition().equals(burrow.pocket());
        for (int dy = 0; sealed && dy < 2; dy++) {
            for (var side : Direction.Plane.HORIZONTAL) {
                var pos = burrow.pocket().above(dy).relative(side);
                if (!burrow.level().isLoaded(pos) || burrow.level().getBlockState(pos).getCollisionShape(burrow.level(), pos).isEmpty()) {
                    sealed = false; break;
                }
            }
        }
        if (!sealed) hidden.remove(hunter.getUUID());
        return sealed;
    }

    public static void stop(MinecraftServer server) { HIDDEN.remove(server); }
}
