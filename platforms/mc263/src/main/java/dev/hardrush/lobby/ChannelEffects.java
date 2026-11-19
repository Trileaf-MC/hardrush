package dev.hardrush.lobby;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What a monster's return or retrack channel looks and sounds like. A return charges like a native
 * respawn anchor: four rising charge chimes and thickening reverse-portal particles, so humans nearby
 * can see it is about to escape. A retrack uses sculk instead. The bound item's native cooldown
 * overlay sweeps across the channel as its progress bar.
 */
final class ChannelEffects {
    private static final int STAGES = 4;
    private static final Map<UUID, Integer> STAGE = new HashMap<>();

    private ChannelEffects() {}

    /** Only the player's own movement keys break a return channel; water, bubble columns and pushes do not. */
    static boolean moving(ServerPlayer player) {
        var input = player.getLastClientInput();
        return input.forward() || input.backward() || input.left() || input.right() || input.jump();
    }

    static void start(ServerPlayer player, boolean retrack, long durationNanos) {
        STAGE.put(player.getUUID(), 0);
        int ticks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, durationNanos / 50_000_000L));
        player.getCooldowns().addCooldown(group(player, retrack), ticks);
        tick(player, retrack, 0, durationNanos);
    }

    static void tick(ServerPlayer player, boolean retrack, long elapsedNanos, long durationNanos) {
        int stage = (int) Math.min(STAGES, 1 + elapsedNanos * STAGES / Math.max(1, durationNanos));
        if (STAGE.getOrDefault(player.getUUID(), 0) < stage) {
            STAGE.put(player.getUUID(), stage);
            float pitch = .8f + .2f * (stage - 1);
            play(player.level(), player.position(), retrack ? SoundEvents.SCULK_CLICKING : SoundEvents.RESPAWN_ANCHOR_CHARGE, 1f, pitch);
        }
        if (player.tickCount % 2 != 0) return;
        player.level().sendParticles(particle(retrack), player.getX(), player.getY() + .9, player.getZ(),
                stage * 2, .35, .7, .35, retrack ? .01 : .05);
    }

    /** The channel ended without interruption: clear the overlay, the retrack search or the return continues. */
    static void complete(ServerPlayer player, boolean retrack) {
        STAGE.remove(player.getUUID());
        clearOverlay(player, retrack);
    }

    /** The body left {@code from}: a burst stays behind, and the monster hears the anchor deplete in camp. */
    static void departed(ServerPlayer player, ServerLevel level, Vec3 from, boolean retrack) {
        level.sendParticles(particle(retrack), from.x, from.y + .9, from.z, 40, .4, .8, .4, .2);
        var sound = retrack ? SoundEvents.SCULK_CLICKING_STOP : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value();
        play(level, from, sound, 1f, 1f);
        if (!retrack && player.connection != null && player.connection.isAcceptingMessages())
            player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.HOSTILE,
                    player.getX(), player.getY(), player.getZ(), 1f, 1f, player.getRandom().nextLong()));
    }

    /** Whoever broke the channel hears it fizzle out. */
    static void interrupted(ServerPlayer player, boolean retrack) {
        STAGE.remove(player.getUUID());
        clearOverlay(player, retrack);
        play(player.level(), player.position(), SoundEvents.BEACON_DEACTIVATE, .7f, 1.4f);
        player.level().sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + .9, player.getZ(), 8, .3, .5, .3, .02);
    }

    private static void clearOverlay(ServerPlayer player, boolean retrack) {
        player.getCooldowns().removeCooldown(group(player, retrack));
    }

    /** Cooldowns belong to the item kind, so the overlay follows the control into any slot or onto the cursor. */
    private static net.minecraft.resources.Identifier group(ServerPlayer player, boolean retrack) {
        return retrack ? dev.hardrush.hunt.HuntRetracks.cooldownGroup(player)
                : player.getCooldowns().getCooldownGroup(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ECHO_SHARD));
    }

    private static SimpleParticleType particle(boolean retrack) {
        return retrack ? ParticleTypes.SCULK_SOUL : ParticleTypes.REVERSE_PORTAL;
    }

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, pitch);
    }
}
