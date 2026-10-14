package dev.hardrush.p0.disguise.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Builds a copied vanilla profile packet without altering the original packet. */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoPacketAccessor {
    @Mutable
    @Accessor("entries")
    void hardrush$entries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
