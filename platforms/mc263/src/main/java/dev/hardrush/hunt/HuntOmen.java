package dev.hardrush.hunt;

import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The target's side of a camp sortie landing: the vanilla cave mood sound, sent to the target alone.
 * Bound to the target's own body, so it follows them and carries no direction; nobody else hears it.
 * When it plays is {@link dev.hardrush.core.hunt.HuntOmens}'s call.
 */
public final class HuntOmen {
    private HuntOmen() {}

    /** Same source, volume and pitch as vanilla's own cave mood ({@code SimpleSoundInstance.forAmbientMood}). */
    public static void play(ServerPlayer target) {
        target.connection.send(new ClientboundSoundEntityPacket(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, target,
                1.0F, 1.0F, target.getRandom().nextLong()));
    }
}
