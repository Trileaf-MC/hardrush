package dev.hardrush.gametest.mixin;

import dev.hardrush.gametest.UnmanagedProfileFixtures;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.player.ProfileTagged;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The production login gate contains no mock-player exemption. */
@Mixin(value=PlayerProfiles.class,remap=false)
public abstract class UnmanagedProfileLoginMixin {
    @Inject(method="beforeLogin",at=@At("HEAD"),cancellable=true)
    private static void before(ServerPlayer player,CallbackInfo ci){if(UnmanagedProfileFixtures.active()){((ProfileTagged)player).hardrush$isolated(false);ci.cancel();}}
    @Inject(method="afterLogin",at=@At("HEAD"),cancellable=true)
    private static void after(ServerPlayer player,CallbackInfo ci){if(UnmanagedProfileFixtures.active())ci.cancel();}
}
